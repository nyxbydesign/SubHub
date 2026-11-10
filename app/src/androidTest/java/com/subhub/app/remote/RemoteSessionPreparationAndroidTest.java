package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.Direction.INCOMING;

import android.content.Context;
import android.database.sqlite.SQLiteException;
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

/** Real SQLite transactions only; preparation never applies a native session. */
@RunWith(AndroidJUnit4.class)
public final class RemoteSessionPreparationAndroidTest {
    private static final String DOM = repeat("a"), SUB = repeat("b"), FENCE = repeat("c");
    private static final String PREFIX = "session_native_prepared:";
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<RemoteStore> handles = new ArrayList<>();
    private String name;
    private static String repeat(String value) { return String.join("", Collections.nCopies(64, value)); }
    private static RemoteSessionClock.Sample clock(long value) { return new RemoteSessionClock.Sample(value, value, 1); }
    private static Request request(String id, long sequence) {
        return Request.start(id, DOM, SUB, "generation", sequence, 7, 1000, 121000, FENCE,
                new Duration(-2, MIN_DURATION_MILLIS, MAX_DURATION_MILLIS, false));
    }
    private RemoteStore open() {
        if (name == null) name = "session-preparation-" + UUID.randomUUID() + ".db";
        RemoteStore store = new RemoteStore(context, name); handles.add(store); return store;
    }
    private RemoteStore fixture() {
        RemoteStore store = open();
        store.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,'generation','Synthetic','SUB','ACTIVE')",
                new Object[]{DOM});
        return store;
    }
    private static String canonical(JSONObject value) throws Exception { return RemoteProtocol.canonical(value); }
    private static JSONObject start(Request request) throws Exception { return RemoteSessionNative.prepare(request, clock(1002)).json(); }
    @After public void cleanup() {
        for (RemoteStore store : handles) store.close();
        if (name != null) context.deleteDatabase(name);
    }
    @Test public void preparationAndApplyingRoundTripTogetherWithoutChoosingAgain() throws Exception {
        RemoteStore first = fixture(); Request request = request("once", 1);
        first.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        assertNull(first.nativeSessionPreparation(DOM, "generation", request.id)); JSONObject prepared = start(request);
        assertEquals(State.APPLYING, first.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002)).result.state);
        first.close(); RemoteStore reopened = open();
        assertEquals(canonical(prepared), canonical(reopened.nativeSessionPreparation(DOM, "generation", request.id)));
        assertEquals(State.APPLYING, reopened.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1003)).result.state);
        assertEquals(canonical(prepared), reopened.metadata(DOM, "generation", PREFIX + request.id, ""));
        assertThrows(SecurityException.class, () -> reopened.prepareNativeSessionControl(DOM, "generation", request.id, start(request), clock(1004)));
        assertEquals(canonical(prepared), canonical(reopened.nativeSessionPreparation(DOM, "generation", request.id)));
        assertEquals(6, reopened.getReadableDatabase().getVersion());
    }
    @Test public void journalUpdateFailureRollsBackPreparationInTheSameRealTransaction() throws Exception {
        RemoteStore store = fixture(); Request request = request("rollback", 1); JSONObject prepared = start(request);
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        store.getWritableDatabase().execSQL("CREATE TRIGGER refuse_applying BEFORE UPDATE OF state ON session_control_journal "
                + "WHEN NEW.state='APPLYING' BEGIN SELECT RAISE(ABORT,'synthetic update refusal'); END");
        assertThrows(SQLiteException.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002)));
        assertEquals("", store.metadata(DOM, "generation", PREFIX + request.id, ""));
        assertEquals(State.ACKNOWLEDGED, store.sessionControl(DOM, "generation", INCOMING, request.id).result.state);
        store.getWritableDatabase().execSQL("DROP TRIGGER refuse_applying");
        assertEquals(State.APPLYING, store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1003)).result.state);
        assertEquals(canonical(prepared), canonical(store.nativeSessionPreparation(DOM, "generation", request.id)));
    }
    @Test public void preparationMustStrictlyMatchTheRecordedRequest() throws Exception {
        RemoteStore store = fixture(); Request request = request("strict", 1);
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        assertThrows(SecurityException.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id, start(request("other", 2)), clock(1002)));
        JSONObject extra = start(request).put("unexpected", true);
        assertThrows(Exception.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id, extra, clock(1002)));
        JSONObject changedDigest = start(request).put("requestDigest", FENCE);
        assertThrows(SecurityException.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id, changedDigest, clock(1002)));
        assertEquals("", store.metadata(DOM, "generation", PREFIX + request.id, ""));
        assertEquals(State.ACKNOWLEDGED, store.sessionControl(DOM, "generation", INCOMING, request.id).result.state);
    }
    private static boolean prepareOrConflict(RemoteStore store, Request request, JSONObject prepared) throws Exception {
        try { store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002)); return true; }
        catch (SecurityException expected) { return false; }
    }
    @Test public void independentHandlesChooseOneImmutablePreparation() throws Exception {
        RemoteStore first = fixture(), second = open(); second.getWritableDatabase();
        Request request = request("race", 1); first.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        JSONObject left = start(request), right = start(request); CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return prepareOrConflict(first, request, left); });
            Future<Boolean> b = workers.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return prepareOrConflict(second, request, right); });
            start.countDown(); boolean wonLeft = a.get(15, TimeUnit.SECONDS), wonRight = b.get(15, TimeUnit.SECONDS);
            assertTrue("Exactly one preparation must win", wonLeft ^ wonRight);
            String winner = canonical(wonLeft ? left : right);
            assertEquals(winner, canonical(first.nativeSessionPreparation(DOM, "generation", request.id)));
            assertEquals(winner, canonical(second.nativeSessionPreparation(DOM, "generation", request.id)));
            assertEquals(State.APPLYING, second.sessionControl(DOM, "generation", INCOMING, request.id).result.state);
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test public void expiryBeforePreparationDoesNotInstallItButApplyingRemainsReconcilable() throws Exception {
        RemoteStore store = fixture(); Request expired = request("expired", 1), applying = request("applying", 2);
        store.rememberSessionControl(DOM, "generation", INCOMING, expired, clock(1001));
        store.rememberSessionControl(DOM, "generation", INCOMING, applying, clock(1001));
        JSONObject prepared = start(applying);
        store.prepareNativeSessionControl(DOM, "generation", applying.id, prepared, clock(1002));
        assertEquals(State.EXPIRED, store.prepareNativeSessionControl(DOM, "generation", expired.id, start(expired), clock(121000)).result.state);
        assertEquals("", store.metadata(DOM, "generation", PREFIX + expired.id, ""));
        assertEquals(State.APPLYING, store.checkSessionControlClock(DOM, "generation", INCOMING, applying.id, clock(121000)).result.state);
        assertEquals(canonical(prepared), canonical(store.nativeSessionPreparation(DOM, "generation", applying.id)));
    }
    @Test public void storedMissingOversizedOrChangedPreparationCannotBecomeEvidence() throws Exception {
        RemoteStore store = fixture(); Request request = request("corrupt", 1); JSONObject prepared = start(request);
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002));
        store.putMetadata(DOM, "generation", PREFIX + request.id, "");
        assertThrows(SecurityException.class, () -> store.nativeSessionPreparation(DOM, "generation", request.id));
        store.putMetadata(DOM, "generation", PREFIX + request.id, String.join("", Collections.nCopies(8193, "x")));
        assertThrows(SecurityException.class, () -> store.nativeSessionPreparation(DOM, "generation", request.id));
        store.putMetadata(DOM, "generation", PREFIX + request.id, canonical(prepared.put("requestDigest", FENCE)));
        assertThrows(SecurityException.class, () -> store.nativeSessionPreparation(DOM, "generation", request.id));
    }
    @Test public void revokedGenerationCannotReadOrPrepareNativeWork() throws Exception {
        RemoteStore store = fixture(); Request request = request("revoked", 1); JSONObject prepared = start(request);
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002)); store.revoke(DOM, "generation");
        assertThrows(SecurityException.class, () -> store.nativeSessionPreparation(DOM, "generation", request.id));
        assertThrows(SecurityException.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1003)));
    }
    @Test public void terminalJournalPruningRemovesOnlyItsAssociatedPreparation() throws Exception {
        RemoteStore store = fixture(); Request request = request("retired", 1); JSONObject prepared = start(request);
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002));
        store.advanceSessionControl(DOM, "generation", INCOMING, request.id,
                new Result(request, State.FAILED, Reason.APPLICATION_FAILED, 1003, null).json());
        store.putMetadata(DOM, "generation", "unrelated", "retained");
        Request next = Request.start("new", DOM, SUB, "generation", 2, 7, 130000, 250000, FENCE, request.service);
        store.rememberSessionControl(DOM, "generation", INCOMING, next, clock(130001));
        assertNull(store.sessionControl(DOM, "generation", INCOMING, request.id));
        assertEquals("", store.metadata(DOM, "generation", PREFIX + request.id, ""));
        assertEquals("retained", store.metadata(DOM, "generation", "unrelated", ""));
    }
    @Test public void stopPreparationUsesTheSameAtomicJournalPath() throws Exception {
        RemoteStore store = fixture(); Request request = Request.stop("stop", DOM, SUB, "generation", 1, 7, 1000, 121000, FENCE);
        JSONObject prepared = new JSONObject().put("schema", 1).put("request", request.json()).put("requestDigest", request.digest())
                .put("statistics", UUID.randomUUID().toString()).put("pact", "").put("pause", "").put("capture", "")
                .put("wall", 1002).put("elapsed", 1002).put("boot", 1).put("process", UUID.randomUUID().toString());
        store.rememberSessionControl(DOM, "generation", INCOMING, request, clock(1001));
        assertEquals(State.APPLYING, store.prepareNativeSessionControl(DOM, "generation", request.id, prepared, clock(1002)).result.state);
        assertEquals(canonical(prepared), canonical(store.nativeSessionPreparation(DOM, "generation", request.id)));
        assertThrows(Exception.class, () -> store.prepareNativeSessionControl(DOM, "generation", request.id,
                new JSONObject(canonical(prepared)).put("request", request("wrong-action", 2).json()), clock(1003)));
    }
}
