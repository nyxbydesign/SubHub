package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.*;
import com.subhub.app.remote.RemoteSessionRuntime.Record;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteSessionCommandsAndroidTest {
    private static final String DOM = repeat("a"), SUB = repeat("b"), FENCE = repeat("c");
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<String> names = new ArrayList<>();
    private final List<RemoteStore> stores = new ArrayList<>();
    private static String repeat(String value) { return String.join("", Collections.nCopies(64,value)); }
    private static Request request(String id) { return Request.start(id,DOM,SUB,"generation",7,1000,121000,FENCE,
            new Duration(3600000,MIN_DURATION_MILLIS,MAX_DURATION_MILLIS,false)); }
    private RemoteStore store(boolean sub) {
        String name = "session-journal-" + UUID.randomUUID() + ".db"; names.add(name);
        RemoteStore store = open(name);
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'generation','Synthetic',?,'ACTIVE')",
                new Object[]{sub ? DOM : SUB,sub ? "SUB" : "DOM"});
        return store;
    }
    private RemoteStore open(String name) { RemoteStore store=new RemoteStore(context,name);stores.add(store);return store; }
    @After public void cleanup() { for(RemoteStore store:stores)store.close();for(String name:names)context.deleteDatabase(name); }

    @Test public void replayRetainsOriginalReceiptAndExpiryAcrossReopen() throws Exception {
        RemoteStore sub = store(true); Request request=request("id");
        Record first = sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1001);
        assertEquals(State.ACKNOWLEDGED,first.result.state);assertNull(first.result.proof);
        sub.close();sub=open(names.get(0));
        Record retry = sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,200000);
        assertEquals(1001,retry.result.observedAt);assertEquals(121000,retry.request.expiresAt);
        assertEquals(first.request.digest(),retry.request.digest());
        assertEquals(1,sub.pendingSessionControls(DOM,"generation",Direction.INCOMING,128).size());
    }

    @Test public void conflictingReplayCannotOverwriteOriginal() throws Exception {
        RemoteStore sub=store(true);Request request=request("id");
        sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1001);
        Request changed=Request.parse(request.json().put("appliedRevision",8));
        assertThrows(SecurityException.class,()->sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,changed,1002));
        assertEquals(7,sub.sessionControl(DOM,"generation",Direction.INCOMING,"id").request.appliedRevision);
    }

    @Test public void resultAdvancesAtomicallyAndTerminalEvidenceIsImmutable() throws Exception {
        RemoteStore dom=store(false);Request request=request("id");
        dom.rememberSessionControl(SUB,"generation",Direction.OUTGOING,request,1001);
        Result applied=new Result(request,State.APPLIED,Reason.NONE,1003,new Proof(true,repeat("d"),1002,3601002,3600000));
        JSONObject wrong=applied.json().put("requestDigest",FENCE);
        assertThrows(Exception.class,()->dom.advanceSessionControl(SUB,"generation",Direction.OUTGOING,"id",wrong));
        assertEquals(State.QUEUED,dom.sessionControl(SUB,"generation",Direction.OUTGOING,"id").result.state);
        dom.advanceSessionControl(SUB,"generation",Direction.OUTGOING,"id",applied.json());
        dom.advanceSessionControl(SUB,"generation",Direction.OUTGOING,"id",applied.json());
        assertTrue(dom.pendingSessionControls(SUB,"generation",Direction.OUTGOING,128).isEmpty());
        JSONObject rewrite=new Result(request,State.FAILED,Reason.APPLICATION_FAILED,1004,null).json();
        assertThrows(SecurityException.class,()->dom.advanceSessionControl(SUB,"generation",Direction.OUTGOING,"id",rewrite));
        assertEquals(State.APPLIED,dom.sessionControl(SUB,"generation",Direction.OUTGOING,"id").result.state);
    }

    @Test public void rolesGenerationAndRevocationFenceJournalAccess() throws Exception {
        RemoteStore sub=store(true);Request request=request("id");
        assertThrows(SecurityException.class,()->sub.rememberSessionControl(DOM,"generation",Direction.OUTGOING,request,1001));
        Request other=Request.parse(request.json().put("generation","other"));
        assertThrows(SecurityException.class,()->sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,other,1001));
        sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1001);
        sub.revoke(DOM,"generation");
        assertThrows(SecurityException.class,()->sub.pendingSessionControls(DOM,"generation",Direction.INCOMING,128));
        assertThrows(SecurityException.class,()->sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1002));
        assertThrows(SecurityException.class,()->sub.advanceSessionControl(DOM,"generation",Direction.INCOMING,"id",
                new Result(request,State.REJECTED,Reason.AUTHORITY_CHANGED,1002,null).json()));
    }

    @Test public void pendingScanIsBoundedAndDeterministic() throws Exception {
        RemoteStore sub=store(true);
        for(String id:new String[]{"c","b","a"})sub.rememberSessionControl(DOM,"generation",Direction.INCOMING,request(id),1001);
        List<Record> pending=sub.pendingSessionControls(DOM,"generation",Direction.INCOMING,2);
        assertEquals(2,pending.size());assertEquals("a",pending.get(0).request.id);assertEquals("b",pending.get(1).request.id);
        assertThrows(IllegalArgumentException.class,()->sub.pendingSessionControls(DOM,"generation",Direction.INCOMING,129));
        assertThrows(IllegalArgumentException.class,()->sub.pendingSessionControls(DOM,"generation",Direction.INCOMING,0));
    }

    @Test public void independentDatabaseHandlesSerializeReplayAndConflictingContent() throws Exception {
        RemoteStore first=store(true), second=open(names.get(0));
        second.getWritableDatabase();
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);
            Request request=request("replay");
            Future<Record> a=workers.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));
                return first.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1001);});
            Future<Record> b=workers.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));
                return second.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,1002);});
            start.countDown();
            Record left=a.get(15,TimeUnit.SECONDS), right=b.get(15,TimeUnit.SECONDS);
            assertEquals(left.result.observedAt,right.result.observedAt);
            assertEquals(left.request.digest(),right.request.digest());

            CountDownLatch conflict=new CountDownLatch(1);
            Request original=request("conflict");
            Request changed=Request.parse(original.json().put("appliedRevision",8));
            Future<Boolean> originalWon=workers.submit(()->{assertTrue(conflict.await(5,TimeUnit.SECONDS));
                return rememberOrConflict(first,original,1003);});
            Future<Boolean> changedWon=workers.submit(()->{assertTrue(conflict.await(5,TimeUnit.SECONDS));
                return rememberOrConflict(second,changed,1004);});
            conflict.countDown();
            boolean acceptedOriginal=originalWon.get(15,TimeUnit.SECONDS);
            boolean acceptedChanged=changedWon.get(15,TimeUnit.SECONDS);
            assertNotEquals(acceptedOriginal,acceptedChanged);
            Record stored=first.sessionControl(DOM,"generation",Direction.INCOMING,"conflict");
            assertEquals(acceptedOriginal?original.digest():changed.digest(),stored.request.digest());
            assertEquals(2,second.pendingSessionControls(DOM,"generation",Direction.INCOMING,128).size());
        } finally {
            workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
        }
    }

    private static boolean rememberOrConflict(RemoteStore store,Request request,long now) throws Exception {
        try {store.rememberSessionControl(DOM,"generation",Direction.INCOMING,request,now);return true;}
        catch(SecurityException expected){return false;}
    }

    @Test public void versionFourUpgradePreservesAllExistingLogicalRows() throws Exception {
        RemoteStore old=store(true);String name=names.get(0);SQLiteDatabase db=old.getWritableDatabase();
        db.execSQL("INSERT INTO remote_metadata VALUES(?,'generation','retained','value')",new Object[]{DOM});
        db.execSQL("INSERT INTO outgoing(peer,generation,stream,next_seq,acked) VALUES(?,'generation','STATS',4,2)",new Object[]{DOM});
        db.execSQL("INSERT INTO outbox VALUES(?,'generation','STATS','summary',4,'{\"count\":9}',0,1000)",new Object[]{DOM});
        String before=StorageCompressionAndroidTest.digest(db);
        db.execSQL("DROP TABLE session_controls");db.setVersion(4);old.close();
        RemoteStore upgraded=open(name);
        assertEquals(RemoteStore.DATABASE_VERSION,upgraded.getReadableDatabase().getVersion());
        assertEquals(before,StorageCompressionAndroidTest.digest(upgraded.getReadableDatabase()));
        assertEquals("value",upgraded.metadata(DOM,"generation","retained","missing"));
        assertEquals(2,upgraded.nextBatch(DOM,"generation",RemoteProtocol.Stream.STATS).after);
        assertTrue(upgraded.pendingSessionControls(DOM,"generation",Direction.INCOMING,1).isEmpty());
    }
}
