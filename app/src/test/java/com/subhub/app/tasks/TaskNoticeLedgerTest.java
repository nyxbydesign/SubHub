package com.subhub.app.tasks;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TaskNoticeLedgerTest {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static TaskNoticeLedger.Receipt receipt(String generation, String key, long at) {
        return new TaskNoticeLedger.Receipt(generation, TaskNoticeLedger.Audience.SUB_TASKS, key, at);
    }
    private static final class Memory {
        final Map<String, Object> saved = new LinkedHashMap<>();
        boolean failCommit;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAll")) return new LinkedHashMap<>(saved);
                    if (method.getName().equals("edit")) return editor();
                    throw new UnsupportedOperationException(method.getName());
                });
        private SharedPreferences.Editor editor() {
            Map<String, Object> pending = new LinkedHashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putString")) { pending.put((String) args[0], args[1]); return proxy; }
                        if (method.getName().equals("remove")) { pending.put((String) args[0], null); return proxy; }
                        if (method.getName().equals("commit")) {
                            // Model Android's in-memory mutation even when persistence acknowledgement fails.
                            pending.forEach((key, value) -> { if (value == null) saved.remove(key); else saved.put(key, value); });
                            return !failCommit;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
        TaskNoticeLedger ledger() { return new TaskNoticeLedger(preferences, () -> { }); }
    }
    @Test public void admissionReopensIdempotentlyAndPreservesFirstClock() {
        Memory m = new Memory(); TaskNoticeLedger ledger = m.ledger();
        TaskNoticeLedger.Receipt first = ledger.admit(receipt(A, "sub_started:one", 1000));
        Map<String, Object> saved = new LinkedHashMap<>(m.saved);
        assertEquals(first, m.ledger().admit(receipt(A, "sub_started:one", 2000)));
        assertEquals(saved, m.saved); assertEquals(List.of(first), m.ledger().list());
        assertThrows(UnsupportedOperationException.class, () -> ledger.list().clear());
    }
    @Test public void exactCancellationRetainsOtherGenerationsAndRejectsStaleReceipt() {
        Memory m = new Memory(); TaskNoticeLedger ledger = m.ledger();
        TaskNoticeLedger.Receipt old = ledger.admit(receipt(A, "sub_started:one", 1000));
        TaskNoticeLedger.Receipt current = ledger.admit(receipt(B, "sub_started:one", 2000));
        assertThrows(IllegalStateException.class, () -> ledger.removeAfterCancellation(receipt(A, "sub_started:one", 999)));
        assertEquals(2, ledger.list().size());
        ledger.removeAfterCancellation(old); ledger.removeAfterCancellation(old);
        assertEquals(List.of(current), ledger.list());
        assertNotEquals(old.tag(), current.tag()); assertEquals(2161, old.id());
    }
    @Test public void fullCapacityDefersWithoutTrimmingAnyRecoverableReceipt() {
        Memory m = new Memory(); TaskNoticeLedger ledger = m.ledger();
        for (int i = 0; i < TaskNoticeLedger.MAX_RECEIPTS; i++) ledger.admit(receipt(A, "notice:" + i, i + 1));
        Map<String, Object> before = new LinkedHashMap<>(m.saved);
        assertThrows(TaskNoticeLedger.CapacityException.class, () -> ledger.admit(receipt(B, "next", 1000)));
        assertEquals(before, m.saved); assertEquals(128, ledger.list().size());
        ledger.removeAfterCancellation(ledger.list().get(0));
        ledger.admit(receipt(B, "next", 1000)); assertEquals(128, ledger.list().size());
    }
    @Test public void unacknowledgedPersistenceFencesTheHandleEvenWhenMemoryChanged() {
        Memory m = new Memory(); TaskNoticeLedger ledger = m.ledger(); m.failCommit = true;
        assertThrows(IllegalStateException.class, () -> ledger.admit(receipt(A, "one", 1)));
        assertEquals(1, m.saved.size()); m.failCommit = false;
        assertThrows(IllegalStateException.class, ledger::list);
        assertThrows(IllegalStateException.class, () -> ledger.removeAfterCancellation(receipt(A, "one", 1)));
        assertEquals(1, m.saved.size());
    }
    @Test public void malformedNamespaceFailsWithoutOverwritingOrClearingEvidence() {
        Memory m = new Memory(); m.saved.put("unknown", "unreadable receipt");
        TaskNoticeLedger ledger = m.ledger(); Map<String, Object> before = new LinkedHashMap<>(m.saved);
        assertThrows(IllegalStateException.class, ledger::list);
        assertThrows(IllegalStateException.class, () -> ledger.admit(receipt(A, "one", 1)));
        assertEquals(before, m.saved);
    }
    @Test public void callerAdmissionCannotBeReplacedByAReceiptOrGenerationString() {
        Memory m = new Memory(); AtomicBoolean allowed = new AtomicBoolean(false);
        TaskNoticeLedger ledger = new TaskNoticeLedger(m.preferences, () -> { if (!allowed.get()) throw new SecurityException("No data admission"); });
        assertThrows(SecurityException.class, () -> ledger.admit(receipt(A, "one", 1)));
        assertTrue(m.saved.isEmpty()); allowed.set(true); ledger.admit(receipt(A, "one", 1));
        allowed.set(false); assertThrows(SecurityException.class, ledger::list);
        assertThrows(SecurityException.class, () -> ledger.removeAfterCancellation(receipt(A, "one", 1)));
        assertEquals(1, m.saved.size());
    }
}
