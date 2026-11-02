package com.subhub.app.remote;

import static org.junit.Assert.*;
import static com.subhub.app.remote.RemoteSessionCommands.*;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real SQLite admission and rollback; no Peer size guard or native permission fixture supplies the bound. */
public final class RemoteSessionWireStoreAndroidTest {
    private static final String DOM = String.join("", Collections.nCopies(64, "a"));
    private static final String SUB = String.join("", Collections.nCopies(64, "b")), GEN = "wire_store_generation";
    private Context target;
    private RemoteStore dom, sub;
    private String domDatabase, subDatabase;
    private RemoteSessionClock.Sample clock;
    private static final RemoteSessionRuntime.Direction OUT = RemoteSessionRuntime.Direction.OUTGOING;
    private static final RemoteSessionRuntime.Direction IN = RemoteSessionRuntime.Direction.INCOMING;
    @Before public void setup() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext(); String prefix = "wire-store-" + UUID.randomUUID();
        domDatabase = prefix + "-dom.db"; subDatabase = prefix + "-sub.db";
        dom = new RemoteStore(target, domDatabase); sub = new RemoteStore(target, subDatabase);
        dom.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic','DOM','ACTIVE')", new Object[]{SUB, GEN});
        sub.getWritableDatabase().execSQL("INSERT INTO peers(peer,generation,name,local_role,state) VALUES(?,?,'Synthetic','SUB','ACTIVE')", new Object[]{DOM, GEN});
        dom.putMetadata(SUB, GEN, "session_control_version", "1"); sub.putMetadata(DOM, GEN, "session_control_version", "1");
        clock = RemoteSessionClock.observe(target);
    }
    @After public void cleanup() {
        if (dom != null) dom.close(); if (sub != null) sub.close();
        if (domDatabase != null) assertTrue(target.deleteDatabase(domDatabase));
        if (subDatabase != null) assertTrue(target.deleteDatabase(subDatabase));
    }
    private static String peer(RemoteSessionRuntime.Direction direction) { return direction == OUT ? SUB : DOM; }
    private Request request(long sequence) throws Exception {
        return Request.stop(UUID.randomUUID().toString(), DOM, SUB, GEN, sequence, 0, clock.wall, clock.wall + 120000,
                String.join("", Collections.nCopies(64, "c")));
    }
    private void remember(RemoteStore store, RemoteSessionRuntime.Direction direction, Request request) throws Exception {
        store.rememberSessionControl(peer(direction), GEN, direction, request, clock);
    }
    private RemoteSessionRuntime.Record record(RemoteStore store, RemoteSessionRuntime.Direction direction, Request request) throws Exception {
        return store.sessionControl(peer(direction), GEN, direction, request.id);
    }
    private RemoteSessionRuntime.Record terminal(RemoteStore store, RemoteSessionRuntime.Direction direction, Request request) throws Exception {
        return store.advanceSessionControl(peer(direction), GEN, direction, request.id,
                new Result(request, State.REJECTED, Reason.SESSION_CHANGED, clock.wall, null).json());
    }
    private String flag(RemoteStore store, RemoteSessionRuntime.Direction direction, Request request, String field) {
        return store.metadata(peer(direction), GEN, "session_control_wire:" + direction.name() + ":" + request.id + ":" + field, "");
    }
    private interface Work { void run() throws Exception; }
    private static void transaction(RemoteStore store, Work work) throws Exception {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try { work.run(); db.setTransactionSuccessful(); } finally { db.endTransaction(); }
        }
    }
    private void retain(Request request) throws Exception { transaction(dom, () -> dom.retainSessionControlAck(SUB, GEN, request.id)); }
    private void clear(Request request) throws Exception {
        RemoteSessionControlAck ack = RemoteSessionControlAck.of(record(dom, OUT, request));
        transaction(dom, () -> dom.clearSessionControlAck(SUB, GEN, ack));
    }
    private int count(RemoteStore store) {
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM session_control_journal", null)) {
            assertTrue(rows.moveToFirst()); return rows.getInt(0);
        }
    }
    @Test public void clearedAckCannotReoccupyFullQueueButSamePendingAckIsIdempotentAtCapacity() throws Exception {
        Request old = request(1); remember(dom, OUT, old); terminal(dom, OUT, old); retain(old); clear(old);
        String immutable = RemoteProtocol.canonical(record(dom, OUT, old).result.json());
        Request first = null;
        for (int index = 2; index <= 17; index++) { Request next = request(index); remember(dom, OUT, next); if (first == null) first = next; }
        assertThrows(IllegalStateException.class, () -> retain(old));
        assertEquals("", flag(dom, OUT, old, "ackPending")); assertEquals(16, dom.pendingSessionControls(SUB, GEN, OUT, 16).size());
        assertEquals("17", dom.metadata(SUB, GEN, "session_control_OUTGOING_floor", "0"));
        assertEquals(immutable, RemoteProtocol.canonical(record(dom, OUT, old).result.json()));
        terminal(dom, OUT, first); retain(first); String digest = flag(dom, OUT, first, "ackPending");
        retain(first); assertEquals(digest, flag(dom, OUT, first, "ackPending"));
        assertEquals(15, dom.pendingSessionControls(SUB, GEN, OUT, 16).size()); assertEquals(1, dom.pendingSessionControlAcks(SUB, GEN).size());
    }
    @Test public void ackCapacityFailureRollsBackEarlierRowProvenanceAndSequenceFloor() throws Exception {
        Request old = request(1); remember(dom, OUT, old); terminal(dom, OUT, old); retain(old); clear(old);
        for (int index = 2; index <= 16; index++) remember(dom, OUT, request(index));
        Request next = request(17);
        assertThrows(IllegalStateException.class, () -> transaction(dom, () -> { remember(dom, OUT, next); dom.retainSessionControlAck(SUB, GEN, old.id); }));
        assertNull(record(dom, OUT, next)); assertEquals("", flag(dom, OUT, next, "protected")); assertEquals("", flag(dom, OUT, old, "ackPending"));
        assertEquals("16", dom.metadata(SUB, GEN, "session_control_OUTGOING_floor", "0")); assertEquals(16, count(dom));
        assertEquals(15, dom.pendingSessionControls(SUB, GEN, OUT, 16).size()); assertTrue(dom.pendingSessionControlAcks(SUB, GEN).isEmpty());
        assertEquals(State.REJECTED, record(dom, OUT, old).result.state);
    }
    @Test public void laterCapabilityCannotPromoteLegacyTerminalDuplicateAtFullCapacity() throws Exception {
        sub.putMetadata(DOM, GEN, "session_control_version", "0"); Request old = request(1); remember(sub, IN, old);
        RemoteSessionRuntime.Record original = terminal(sub, IN, old); String immutable = RemoteProtocol.canonical(original.result.json());
        sub.putMetadata(DOM, GEN, "session_control_version", "1");
        for (int index = 2; index <= 17; index++) remember(sub, IN, request(index));
        transaction(sub, () -> {
            remember(sub, IN, old);
            sub.acknowledgeSessionControl(DOM, GEN, RemoteSessionControlAck.of(original));
        });
        assertEquals("", flag(sub, IN, old, "protected")); assertEquals("", flag(sub, IN, old, "acked"));
        assertEquals(immutable, RemoteProtocol.canonical(record(sub, IN, old).result.json()));
        assertEquals(original.lifetime.receivedWall, record(sub, IN, old).lifetime.receivedWall);
        assertEquals(original.lifetime.deadlineElapsed, record(sub, IN, old).lifetime.deadlineElapsed);
        assertEquals(16, sub.pendingSessionControls(DOM, GEN, IN, 16).size()); assertEquals(17, count(sub));
        Request next = request(18); assertThrows(IllegalStateException.class, () -> remember(sub, IN, next));
        assertNull(record(sub, IN, next)); assertEquals("17", sub.metadata(DOM, GEN, "session_control_INCOMING_floor", "0"));
    }
    @Test public void laterCapabilityCannotTurnLegacyUnpreparedReceiptIntoNegotiatedIntent() throws Exception {
        sub.putMetadata(DOM, GEN, "session_control_version", "0"); Request old = request(1); remember(sub, IN, old);
        RemoteSessionRuntime.Record original = record(sub, IN, old); String immutable = RemoteProtocol.canonical(original.result.json());
        sub.putMetadata(DOM, GEN, "session_control_version", "1");
        assertThrows(SecurityException.class, () -> transaction(sub, () -> remember(sub, IN, old)));
        assertEquals("", flag(sub, IN, old, "protected")); assertEquals(immutable, RemoteProtocol.canonical(record(sub, IN, old).result.json()));
        assertEquals(original.request.digest(), record(sub, IN, old).request.digest()); assertEquals(original.request.expiresAt, record(sub, IN, old).request.expiresAt);
        assertEquals("1", sub.metadata(DOM, GEN, "session_control_INCOMING_floor", "0")); assertEquals(1, count(sub));
    }
}
