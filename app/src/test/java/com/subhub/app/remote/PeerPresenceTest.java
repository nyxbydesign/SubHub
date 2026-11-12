package com.subhub.app.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PeerPresenceTest {
    @Test public void foregroundObservationExpiresWithoutTransportExtendingIt() {
        assertTrue(PeerPresence.online(true,100,100));
        assertTrue(PeerPresence.online(true,100,90_099));
        assertFalse(PeerPresence.online(true,100,90_100));
        assertFalse(PeerPresence.online(false,100,100));
        assertFalse(PeerPresence.online(true,101,100));
    }
    @Test public void legacyBackgroundAllowsDataPresenceUntilItsShortLeaseExpires() throws Exception {
        JSONObject saved = new JSONObject();
        assertTrue(PeerPresence.recordForeground(saved, 10, false, 1_000, 100, "current"));
        PeerPresence.recordData(saved, 1_100, 200, "current");
        assertTrue(PeerPresence.recordForeground(saved, 11, false, 1_200, 300, "current"));
        PeerPresence.Observation active = PeerPresence.decode(saved.toString(), 1_200, 300, "current");
        assertTrue(active.online);
        assertEquals(1_100, active.lastOnlineAt);
        assertEquals(29_900, active.expiresInMillis);
        assertTrue(PeerPresence.decode(saved.toString(), 32_000, 30_199, "current").online);
        PeerPresence.Observation expired = PeerPresence.decode(saved.toString(), 32_000, 30_200, "current");
        assertFalse(expired.online);
        assertEquals(1_100, expired.lastOnlineAt);
    }

    @Test public void foregroundAndDataExpireIndependentlyAndDoNotCrossProcessRestart() throws Exception {
        JSONObject saved = new JSONObject();
        assertTrue(PeerPresence.recordForeground(saved, 10, true, 1_000, 100, "first"));
        PeerPresence.recordData(saved, 1_100, 200, "first");
        assertEquals(89_900, PeerPresence.decode(saved.toString(), 1_200, 200, "first").expiresInMillis);
        assertTrue(PeerPresence.recordForeground(saved, 11, false, 1_200, 300, "first"));
        assertEquals(29_900, PeerPresence.decode(saved.toString(), 1_200, 300, "first").expiresInMillis);
        assertFalse(PeerPresence.recordForeground(saved, 10, true, 1_300, 400, "first"));
        assertFalse(PeerPresence.decode(saved.toString(), 1_300, 400, "second").online);
        assertEquals(1_100, PeerPresence.decode(saved.toString(), 1_300, 400, "second").lastOnlineAt);
        PeerPresence.recordData(saved, 1_400, 500, "second");
        assertEquals(30_000, PeerPresence.decode(saved.toString(), 1_400, 500, "second").expiresInMillis);
        assertFalse(PeerPresence.decode(saved.toString(), 1_399, 500, "second").online);
    }

    @Test public void enabledBackgroundRemainsIdleDuringDataAndExpiresWithoutChangingLastSeen() throws Exception {
        JSONObject saved = new JSONObject();
        assertTrue(PeerPresence.recordForeground(saved, 10, true, true, 1_000, 100, "current"));
        assertEquals(PeerPresence.Status.ONLINE, PeerPresence.decode(saved.toString(), 1_000, 100, "current").status);
        assertTrue(PeerPresence.recordForeground(saved, 11, false, true, 1_100, 200, "current"));
        assertFalse(PeerPresence.recordData(saved, 1_200, 300, "current"));
        PeerPresence.Observation idle = PeerPresence.decode(saved.toString(), 1_200, 300, "current");
        assertEquals(PeerPresence.Status.IDLE, idle.status);
        assertEquals(1_000, idle.lastOnlineAt);
        assertEquals(119_900, idle.expiresInMillis);
        assertEquals(PeerPresence.Status.IDLE, PeerPresence.decode(saved.toString(), 130_000, 120_199, "current").status);
        PeerPresence.Observation expired = PeerPresence.decode(saved.toString(), 130_000, 120_200, "current");
        assertEquals(PeerPresence.Status.OFFLINE, expired.status);
        assertEquals(1_000, expired.lastOnlineAt);
        assertEquals(PeerPresence.Status.OFFLINE, PeerPresence.decode(saved.toString(), 1_200, 300, "restart").status);
    }

    @Test public void idleRequiresExplicitOrderedOptInAndCanPrecedeFirstOnlineObservation() throws Exception {
        JSONObject saved = new JSONObject();
        assertTrue(PeerPresence.recordForeground(saved, 1, false, true, 1_000, 100, "current"));
        PeerPresence.Observation idle = PeerPresence.decode(saved.toString(), 1_100, 200, "current");
        assertEquals(PeerPresence.Status.IDLE, idle.status);
        assertEquals(0, idle.lastOnlineAt);
        assertTrue(PeerPresence.recordForeground(saved, 2, false, false, 1_200, 300, "current"));
        assertFalse(PeerPresence.recordForeground(saved, 1, false, true, 1_300, 400, "current"));
        assertFalse(PeerPresence.recordData(saved, 1_300, 400, "current"));
        assertEquals(PeerPresence.Status.OFFLINE, PeerPresence.decode(saved.toString(), 1_300, 400, "current").status);
        assertTrue(PeerPresence.recordForeground(saved, 3, false, 1_400, 500, "current"));
        assertEquals(PeerPresence.Status.OFFLINE, PeerPresence.decode(saved.toString(), 1_400, 500, "current").status);
        assertTrue(PeerPresence.recordData(saved, 1_500, 600, "current"));
        assertEquals(PeerPresence.Status.ONLINE, PeerPresence.decode(saved.toString(), 1_500, 600, "current").status);
    }

    @Test public void processRestartWallRollbackAndOfflineNeverGrantOnline() throws Exception {
        JSONObject record=new JSONObject().put("foreground",true).put("receivedElapsed",100)
                .put("process","first").put("lastOnlineAt",1000);
        PeerPresence.Observation live=PeerPresence.decode(record.toString(),1100,200,"first");
        assertTrue(live.online);assertEquals(1000,live.lastOnlineAt);assertEquals(89_900,live.expiresInMillis);
        assertFalse(PeerPresence.decode(record.toString(),1100,200,"second").online);
        assertFalse(PeerPresence.decode(record.toString(),999,200,"first").online);
        record.put("foreground",false);
        PeerPresence.Observation offline=PeerPresence.decode(record.toString(),1200,300,"first");
        assertFalse(offline.online);assertEquals(1000,offline.lastOnlineAt);
        assertFalse(PeerPresence.decode("",1200,300,"first").online);
    }
}
