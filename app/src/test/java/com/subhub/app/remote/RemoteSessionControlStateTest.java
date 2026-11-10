package com.subhub.app.remote;

import static org.junit.Assert.*;

import java.util.Collections;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class RemoteSessionControlStateTest {
    private static String hash(String value) { return String.join("", Collections.nCopies(64, value)); }
    private static RemoteSessionControlState idle(long observed) {
        return new RemoteSessionControlState(hash("a"), hash("b"), "generation", observed, 7,
                new RemoteSessionCommands.Duration(-1, 3600000, 86400000, false), false, hash("c"), "",
                RemoteSessionRuntime.Readiness.APP_MODE_READY, RemoteSessionCommands.Reason.NONE, true, false);
    }
    @Test public void advisoryStateRoundTripsStrictlyWithoutNativeOrConsentCredentials() throws Exception {
        RemoteSessionControlState original = idle(1000), parsed = RemoteSessionControlState.parse(original.json());
        assertEquals(RemoteProtocol.canonical(original.json()), RemoteProtocol.canonical(parsed.json()));
        assertEquals(original.witness(), parsed.witness());
        assertThrows(JSONException.class, () -> RemoteSessionControlState.parse(original.json().put("proof", "credential")));
        assertThrows(JSONException.class, () -> RemoteSessionControlState.parse(original.json().put("canStart", "true")));
        assertThrows(JSONException.class, () -> RemoteSessionControlState.parse(original.json().put("schema", 2)));
    }
    @Test public void repeatedObservationDoesNotInvalidateReviewButChangedFactsDo() throws Exception {
        assertEquals(idle(1000).witness(), idle(2000).witness());
        RemoteSessionControlState changed = RemoteSessionControlState.parse(idle(2000).json().put("idleFence", hash("d")));
        assertNotEquals(idle(1000).witness(), changed.witness());
        changed = RemoteSessionControlState.parse(idle(2000).json().put("appliedRevision", 8));
        assertNotEquals(idle(1000).witness(), changed.witness());
    }
    @Test public void inconsistentFlagsIdentitiesAndOversizedStateAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> RemoteSessionControlState.parse(idle(1000).json().put("canStop", true)));
        assertThrows(IllegalArgumentException.class, () -> RemoteSessionControlState.parse(idle(1000).json().put("active", true)));
        assertThrows(IllegalArgumentException.class, () -> RemoteSessionControlState.parse(idle(1000).json().put("readiness", "UNAVAILABLE")));
        assertThrows(IllegalArgumentException.class, () -> RemoteSessionControlState.parse(idle(1000).json().put("recipient", hash("a"))));
        assertThrows(JSONException.class, () -> RemoteSessionControlState.parse(idle(1000).json().put("extra", String.join("", Collections.nCopies(4096, "x")))));
    }
    @Test public void remoteFutureClockCannotRenewTheLocalReceiptAgeBudget() throws Exception {
        RemoteSessionControlState.Received received = new RemoteSessionControlState.Received(idle(Long.MAX_VALUE),
                new RemoteSessionClock.Sample(100000, 1000, 7));
        RemoteSessionControlState.Received reopened = RemoteSessionControlState.Received.parse(received.json());
        assertTrue(reopened.fresh(new RemoteSessionClock.Sample(189999, 90999, 7)));
        assertFalse(reopened.fresh(new RemoteSessionClock.Sample(190000, 91000, 7)));
        assertFalse(reopened.fresh(new RemoteSessionClock.Sample(100001, 1001, 8)));
        assertFalse(reopened.fresh(new RemoteSessionClock.Sample(99999, 1001, 7)));
        assertFalse(reopened.fresh(new RemoteSessionClock.Sample(103002, 1001, 7)));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionControlState.Received(idle(1000), new RemoteSessionClock.Sample(1, 1, -1)));
    }
    @Test public void stopAdviceRemainsIndependentOfStartReadinessAndUnavailableService() throws Exception {
        RemoteSessionControlState state = new RemoteSessionControlState(hash("a"), hash("b"), "generation", 1000, 7,
                null, true, "", hash("c"), RemoteSessionRuntime.Readiness.UNAVAILABLE,
                RemoteSessionCommands.Reason.READINESS_REQUIRED, false, true);
        RemoteSessionControlState parsed = RemoteSessionControlState.parse(state.json());
        assertTrue(parsed.canStop); assertFalse(parsed.canStart); assertNull(parsed.service);
        assertEquals(hash("c"), parsed.sessionIdentity);
    }
}
