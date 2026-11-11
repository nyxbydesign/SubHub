package com.subhub.app.tasks;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.internal.*;
import com.subhub.app.people.InternalSubData;
import com.subhub.app.notifications.OptionalNotificationDispatch;
import com.subhub.app.stats.OwnerObservation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native SQL + file ACK fixtures, isolated from application installation and all physical stores. */
@RunWith(AndroidJUnit4.class)
public final class InternalTaskEffectsAndroidTest {
    @Test public void protectionIndependentSweepAssessesOnceInOwnedLedgerWithoutPhysicalReads() throws Exception {
        try (Fixture f = new Fixture()) {
            long now = System.currentTimeMillis(); f.visible(true, now - 300_000);
            f.seed(now - 180_000, now - 170_000);
            f.run(now); f.run(now + 1);
            assertEquals(TaskEngine.Status.TIMED_OUT, f.status(now));
            InternalRuntimeRecord record = f.disk();
            assertEquals(1, record.namespaces().get("assessed_ledger").keySet().stream().filter(key -> key.startsWith("task_failure:")).count());
            assertEquals(0, f.context.unrelated);
            assertTrue(f.broker.submitTask(f.generation(), a -> {
                try { return f.backend.admittedStore(a).pending(InternalTaskBackend.DATA_SCOPE).isEmpty(); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }).get(10, TimeUnit.SECONDS));
        }
    }
    @Test public void hiddenDeadlineIsSkippedOnReaddAndOldGenerationCannotRun() throws Exception {
        try (Fixture f = new Fixture()) {
            long now = System.currentTimeMillis(); f.visible(true, now - 300_000);
            f.seed(now - 180_000, now - 170_000); String old = f.generation();
            f.visible(false, now - 90_000); f.visible(true, now - 60_000);
            assertThrows(ExecutionException.class, () -> f.broker.submitTask(old, a -> { fail("Retired generation admitted"); return null; }).get(10, TimeUnit.SECONDS));
            f.run(now); assertEquals(TaskEngine.Status.SKIPPED, f.status(now));
            assertFalse(f.disk().namespaces().containsKey("assessed_ledger"));
            assertEquals(0, f.context.unrelated);
        }
    }
    @Test public void boundedEffectPagesWrapAndExcludeOtherScopesAcrossInsertDeleteAndRowidReuse() throws Exception {
        try(Fixture f=new Fixture()) {
            long now=System.currentTimeMillis();f.visible(true,now-300_000);f.seed(now-180_000,now-170_000);
            f.broker.submitTask(f.generation(),access->{try {
                TaskStore store=f.backend.admittedStore(access);android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
                db.delete("effects",null,null);
                String body=new TaskAction("page-action","owned_effect_task",TaskAction.Kind.TIMEOUT,f.taskDate,now,"","",List.of(),null).json().toString();
                for(int i=1;i<=5;i++)insertEffect(db,i,InternalTaskBackend.DATA_SCOPE,"notice"+i,"notification",body);
                insertEffect(db,6,InternalTaskBackend.DATA_SCOPE,"failure6","failure",body);
                insertEffect(db,7,"foreign-owned-fixture","foreign7","notification",body);
                assertEquals(List.of(4L,5L),rowIds(store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,3,2,access::requireCurrent)));
                assertEquals(List.of(1L,2L,3L),rowIds(store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,5,3,access::requireCurrent)));
                assertEquals(List.of(3L,4L,5L,1L,2L),rowIds(store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,2,64,access::requireCurrent)));
                assertEquals(List.of(6L),rowIds(store.pendingEffects(InternalTaskBackend.DATA_SCOPE,true,99,64,access::requireCurrent)));
                db.delete("effects","rowid=?",new String[]{"4"});
                insertEffect(db,4,InternalTaskBackend.DATA_SCOPE,"replacement4","notification",body);
                insertEffect(db,8,InternalTaskBackend.DATA_SCOPE,"insert8","notification",body);
                var page=store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,5,64,access::requireCurrent);
                assertEquals(List.of(8L,1L,2L,3L,4L,5L),rowIds(page));assertEquals("replacement4",page.get(4).effect.id);
                assertThrows(IllegalArgumentException.class,()->store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,0,0,access::requireCurrent));
                assertThrows(IllegalArgumentException.class,()->store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,0,65,access::requireCurrent));
                assertThrows(IllegalArgumentException.class,()->store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,-1,1,access::requireCurrent));
                assertThrows(SecurityException.class,()->store.pendingEffects("foreign-owned-fixture",false,0,1,access::requireCurrent));
                try(TaskStore unowned=new TaskStore(f.context)) {
                    assertThrows(SecurityException.class,()->unowned.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,0,1,access::requireCurrent));
                }
                return null;
            }catch(Exception failure){throw new IllegalStateException(failure);}}).get(10,TimeUnit.SECONDS);
        }
    }
    @Test public void retainedTaskAdmissionCannotReadEffectPagesAfterCommandReturns() throws Exception {
        try(Fixture f=new Fixture()) {
            long now=System.currentTimeMillis();f.visible(true,now-300_000);
            java.util.concurrent.atomic.AtomicReference<TaskStore> store=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Runnable> admission=new java.util.concurrent.atomic.AtomicReference<>();
            f.broker.submitTask(f.generation(),access->{store.set(f.backend.admittedStore(access));admission.set(access::requireCurrent);return null;}).get(10,TimeUnit.SECONDS);
            assertThrows(SecurityException.class,()->store.get().pendingEffects(InternalTaskBackend.DATA_SCOPE,false,0,1,admission.get()));
        }
    }
    @Test public void unavailableReviewBacklogCannotStarveNativeFailureLedgerReceipt() throws Exception {
        try(Fixture f=new Fixture()) {
            long now=System.currentTimeMillis();f.visible(true,now-300_000);f.seed(now-180_000,now-170_000);
            f.broker.submitTask(f.generation(),access->{try {
                TaskStore store=f.backend.admittedStore(access);android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
                db.delete("effects",null,null);
                for(int i=1;i<=64;i++) {
                    String body=new TaskAction("review-action"+i,"owned_effect_task",TaskAction.Kind.TIMEOUT,f.taskDate,now-100_000,"","",List.of(),null).json().toString();
                    insertEffect(db,i,InternalTaskBackend.DATA_SCOPE,"review"+i,"notification",body);
                }
                return null;
            }catch(Exception failure){throw new IllegalStateException(failure);}}).get(10,TimeUnit.SECONDS);
            f.run(now,InternalTaskEffectDrain.Notice.RETRY);f.run(now+1,InternalTaskEffectDrain.Notice.RETRY);
            assertEquals(1,f.disk().namespaces().get("assessed_ledger").keySet().stream().filter(k->k.startsWith("task_failure:")).count());
            f.broker.submitTask(f.generation(),access->{try {
                TaskStore store=f.backend.admittedStore(access);
                assertTrue(store.pendingEffects(InternalTaskBackend.DATA_SCOPE,true,0,64,access::requireCurrent).isEmpty());
                assertEquals(64,store.pendingEffects(InternalTaskBackend.DATA_SCOPE,false,0,64,access::requireCurrent).size());return null;
            }catch(Exception failure){throw new IllegalStateException(failure);}}).get(10,TimeUnit.SECONDS);
            assertEquals(0,f.context.unrelated);
        }
    }
    private static void insertEffect(android.database.sqlite.SQLiteDatabase db,long row,String scope,String id,String kind,String body) {
        db.execSQL("INSERT INTO effects(rowid,scope,id,kind,body,done) VALUES(?,?,?,?,?,0)",new Object[]{row,scope,id,kind,body});
    }
    private static List<Long> rowIds(List<InternalTaskEffectDrain.Pending<TaskStore.Effect>> values) {
        List<Long> result=new ArrayList<>();for(var value:values)result.add(value.rowId);return result;
    }
    private static final class Fixture implements AutoCloseable {
        final Context physical = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Path root = Files.createTempDirectory(physical.getCacheDir().toPath(), "internal-task-effects-");
        final OwnedContext context = new OwnedContext(physical);
        final InternalSubData data;
        final InternalOwnerDataBroker broker;
        final InternalTaskBackend backend;
        LocalDate taskDate;
        Fixture() throws Exception {
            FileInternalSubBackend files = FileInternalSubBackend.createNew(root.toFile(),
                    new InternalProfile("Task effects fixture", "", "", "", 0), InternalConfigurationStore.defaults());
            Constructor<InternalSubData> constructor = InternalSubData.class.getDeclaredConstructor(FileInternalSubBackend.class);
            constructor.setAccessible(true); data = constructor.newInstance(files);
            broker = InternalOwnerDataBroker.attach(context, data, OwnerDataProvisioning.Decision.FIRST_INTERNAL_CREATE, r -> r.visible);
            broker.initialize().get(10, TimeUnit.SECONDS);
            backend = InternalTaskBackend.get(context, data);
        }
        String generation() { return data.acknowledged().generation; }
        void visible(boolean visible, long clock) throws Exception {
            CompletableFuture<Void> done = new CompletableFuture<>();
            data.execute(() -> { try { data.store.setVisible(data.store.read(), visible, clock); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); } });
            done.get(10, TimeUnit.SECONDS);
        }
        void seed(long start, long at) throws Exception {
            LocalDateTime first = Instant.ofEpochMilli(start).atZone(ZoneId.of("UTC")).toLocalDateTime().withSecond(0).withNano(0); taskDate = first.toLocalDate();
            TaskDefinition task = new TaskDefinition("owned_effect_task", "Owned effect", "", first, ZoneId.of("UTC"), 60_000, Set.of(), true, true, null);
            broker.submitTask(generation(), access -> {
                try { backend.admittedStore(access).append(InternalTaskBackend.DATA_SCOPE, new TaskAction("owned_effect_create", task.id,
                        TaskAction.Kind.CREATE, null, at, "", "", List.of(), task), true, false); return null; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).get(10, TimeUnit.SECONDS);
        }
        void run(long now) throws Exception {run(now,InternalTaskEffectDrain.Notice.SKIP);}
        void run(long now,InternalTaskEffectDrain.Notice readiness) throws Exception {
            String generation = generation();
            broker.submitTask(generation, access -> {
                try {
                    backend.sweepAdmitted(access, now);
                    backend.effectsAdmitted(access, new OwnerObservation(now, "UTC"),
                            new OptionalNotificationDispatch.InternalTaskOwner(generation, () -> true), new InternalTaskNotifications(context) {
                        @Override InternalTaskEffectDrain.Notice prepare(TaskNoticeLedger.Audience audience) { return readiness; }
                        @Override boolean post(OptionalNotificationDispatch.InternalTaskOwner owner, TaskStore.Effect effect, TaskEngine engine, TaskNoticeLedger.Receipt receipt) { throw new AssertionError("Unexpected OS post"); }
                        @Override void cancel(TaskNoticeLedger.Receipt receipt) { throw new AssertionError("Unexpected OS cancellation"); }
                    }); return null;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }).get(10, TimeUnit.SECONDS);
        }
        TaskEngine.Status status(long now) throws Exception {
            return broker.submitTask(generation(), access -> {
                try { return backend.admittedStore(access).load(InternalTaskBackend.DATA_SCOPE).occurrence("owned_effect_task", taskDate, now).status; }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).get(10, TimeUnit.SECONDS);
        }
        InternalRuntimeRecord disk() throws Exception {
            // The public backend supplies an immutable read; no mutable provider escapes broker work.
            FileInternalRuntimeBackend view = FileInternalRuntimeBackend.openExisting(data.storage);
            try { return view.read(); } finally { view.close(); }
        }
        @Override public void close() throws Exception {
            Throwable problem = null;
            try { broker.closeAfterDrain().get(10, TimeUnit.SECONDS); }
            catch (Throwable failure) { problem = failure; }
            try {
                Field stored = InternalTaskBackend.class.getDeclaredField("store"); stored.setAccessible(true);
                TaskStore store = (TaskStore) stored.get(backend); if (store != null) store.close();
                Field writes = InternalSubData.class.getDeclaredField("writes"); writes.setAccessible(true);
                ExecutorService worker = (ExecutorService) writes.get(data); worker.shutdown();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            } catch (Throwable failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
            try {
                if (problem == null) {
                    for (Class<?> type : new Class<?>[]{InternalTaskBackend.class, InternalOwnerDataBroker.class}) {
                        Field open = type.getDeclaredField("OPEN"); open.setAccessible(true);
                        Map<?, ?> handles = (Map<?, ?>) open.get(null); synchronized (handles) { handles.remove(data); }
                    }
                    for (SharedPreferences prefs : context.owned.values()) assertTrue(prefs.edit().clear().commit());
                    try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                        for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
                    }
                }
            } catch (Throwable failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
            if (problem instanceof Exception) throw (Exception) problem;
            if (problem instanceof Error) throw (Error) problem;
        }
    }
    private static final class OwnedContext extends ContextWrapper {
        final Map<String, SharedPreferences> owned = new HashMap<>(); int unrelated;
        OwnedContext(Context physical) { super(physical); }
        @Override public Context getApplicationContext() { return this; }
        @Override public java.io.File getDatabasePath(String name){throw new AssertionError("Physical database path forbidden");}
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!name.equals("subhub_internal_owner_data_registry") && !name.equals(InternalTaskDatabase.REGISTRY)) {
                unrelated++; throw new AssertionError("Physical preferences forbidden: " + name);
            }
            return owned.computeIfAbsent(name, key -> getBaseContext().getSharedPreferences("internal_effect_fixture_" + UUID.randomUUID(), 0));
        }
    }
}
