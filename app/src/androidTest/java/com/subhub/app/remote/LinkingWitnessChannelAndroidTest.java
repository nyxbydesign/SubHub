package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.database.Cursor;
import android.system.Os;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.junit.Test;

/** Real Android FileChannel interruption and owned descriptor recovery, with unchanged authority receipts. */
public final class LinkingWitnessChannelAndroidTest extends LinkingFixture {
    @Test public void cancelledAndClosedChannelEntriesCannotPoisonLaterReadsOrRepairAuthority() throws Exception {
        assertTrue(await(provider().snapshot()).supported);
        LinkingWitness witness=LinkingWitness.registered(store);
        String before=state();byte[] markerBefore=Files.readAllBytes(marker().toPath());
        Map<String,byte[]> certificates=certificates();
        FileOutputStream original=stream(witness);FileDescriptor oldDescriptor=original.getFD();
        StructStat identity=Os.fstat(oldDescriptor);
        AtomicBoolean ran=new AtomicBoolean();
        Outcome refused=interrupted(()->store.authorityObservation(()->{ran.set(true);return "cancelled";}));
        assertTrue(refused.interrupted);assertTrue(hasCause(refused.failure,InterruptedIOException.class));
        assertFalse(ran.get());assertTrue(original.getChannel().isOpen());assertUnchanged(before,markerBefore,certificates);

        // Reproduce the platform closure that the old cached-stream reuse could not recover from.
        Outcome nativeClose=interrupted(()->{try(FileLock ignored=original.getChannel().lock()){}});
        assertTrue(nativeClose.interrupted);assertTrue(hasCause(nativeClose.failure,FileLockInterruptionException.class));
        assertFalse(original.getChannel().isOpen());assertTrue("Borrowed stream must not own the descriptor",oldDescriptor.valid());
        assertEquals("healthy",store.authorityObservation(()->"healthy"));
        FileOutputStream replacement=stream(witness);assertNotSame(original,replacement);
        assertFalse("The retired owner must close its descriptor",oldDescriptor.valid());
        assertTrue(replacement.getFD().valid());StructStat reopened=Os.fstat(replacement.getFD());
        assertEquals(identity.st_dev,reopened.st_dev);assertEquals(identity.st_ino,reopened.st_ino);
        assertUnchanged(before,markerBefore,certificates);

        ReentrantLock gate=(ReentrantLock)value(witness,"gate");
        ExecutorService worker=Executors.newSingleThreadExecutor();CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<Outcome> cancellation=new AtomicReference<>();ran.set(false);
        try {
            gate.lock();
            try {
                Future<?> queued=worker.submit(()->{
                    started.countDown();Throwable failure=null;
                    try{store.authorityObservation(()->{ran.set(true);return "cancelled";});}
                    catch(Throwable problem){failure=problem;}
                    finally{cancellation.set(new Outcome(failure,Thread.currentThread().isInterrupted()));done.countDown();}
                });
                assertTrue(started.await(5,TimeUnit.SECONDS));assertTrue(queued.cancel(true));
            } finally {gate.unlock();}
            assertTrue(done.await(5,TimeUnit.SECONDS));
        }
        finally{worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        assertTrue(cancellation.get().interrupted);assertTrue(hasCause(cancellation.get().failure,InterruptedIOException.class));
        assertFalse(ran.get());assertTrue(replacement.getChannel().isOpen());
        assertEquals("healthy",store.authorityObservation(()->"healthy"));assertUnchanged(before,markerBefore,certificates);

        // Resource recovery never supplies a replacement persistence receipt or repairs rolled-back SQL.
        store.getWritableDatabase().execSQL("UPDATE linking_state SET revision=revision-1 WHERE id=1");
        String tampered=state();replacement.getChannel().close();ran.set(false);
        assertThrows(IllegalStateException.class,()->store.authorityObservation(()->{ran.set(true);return "invalid";}));
        assertFalse(ran.get());assertEquals(tampered,state());assertArrayEquals(markerBefore,Files.readAllBytes(marker().toPath()));
        assertCertificates(certificates);
        Field failed=LinkingWitness.class.getDeclaredField("failed");failed.setAccessible(true);failed.setBoolean(witness,true);
        stream(witness).getChannel().close();
        assertThrows(IllegalStateException.class,()->store.authorityObservation(()->"invalid"));
        assertTrue(failed.getBoolean(witness));assertEquals(tampered,state());assertCertificates(certificates);
    }

    private static FileOutputStream stream(LinkingWitness witness) throws Exception{return (FileOutputStream)value(witness,"lockStream");}
    private static Object value(LinkingWitness witness,String name) throws Exception {
        Field field=LinkingWitness.class.getDeclaredField(name);field.setAccessible(true);return field.get(witness);
    }
    private String state() throws Exception {
        try(Cursor row=store.getReadableDatabase().rawQuery("SELECT epoch,revision,command,state_digest,init_digest,initialized,ready_revision,snapshot_scope FROM linking_state WHERE id=1",null)) {
            assertTrue(row.moveToFirst());JSONArray values=new JSONArray();for(int i=0;i<row.getColumnCount();i++)values.put(row.isNull(i)?org.json.JSONObject.NULL:row.getString(i));return values.toString();
        }
    }
    private Map<String,byte[]> certificates() throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);Map<String,byte[]> result=new LinkedHashMap<>();
        String prefix="subhub.linking.v1."+LocalRelationshipAuthority.digest(store.resolvedDatabaseFile().getPath())+".";
        for(String kind:new String[]{LinkingReceiptStore.INIT,LinkingReceiptStore.EVER,LinkingReceiptStore.CURRENT}){
            assertNotNull(keys.getCertificate(prefix+kind));result.put(kind,keys.getCertificate(prefix+kind).getEncoded());
        }
        return result;
    }
    private void assertCertificates(Map<String,byte[]> expected) throws Exception {
        Map<String,byte[]> actual=certificates();for(String kind:expected.keySet())assertArrayEquals(expected.get(kind),actual.get(kind));
    }
    private void assertUnchanged(String state,byte[] marker,Map<String,byte[]> certificates) throws Exception {
        assertEquals(state,state());assertArrayEquals(marker,Files.readAllBytes(marker().toPath()));assertCertificates(certificates);
    }
    private interface Action {void run() throws Exception;}
    private static final class Outcome {
        final Throwable failure;final boolean interrupted;
        Outcome(Throwable failure,boolean interrupted){this.failure=failure;this.interrupted=interrupted;}
    }
    private static Outcome interrupted(Action work) throws Exception {
        AtomicReference<Outcome> result=new AtomicReference<>();Thread thread=new Thread(()->{
            Thread.currentThread().interrupt();Throwable failure=null;
            try{work.run();}catch(Throwable problem){failure=problem;}
            finally{result.set(new Outcome(failure,Thread.currentThread().isInterrupted()));}
        },"owned-linking-interruption");
        thread.start();thread.join(5000);
        if(thread.isAlive()){thread.interrupt();thread.join(1000);}
        assertFalse("Owned interruption worker did not stop",thread.isAlive());assertNotNull(result.get());return result.get();
    }
    private static boolean hasCause(Throwable failure,Class<? extends Throwable> type) {
        for(int i=0;failure!=null&&i<8;i++,failure=failure.getCause())if(type.isInstance(failure))return true;return false;
    }
}
