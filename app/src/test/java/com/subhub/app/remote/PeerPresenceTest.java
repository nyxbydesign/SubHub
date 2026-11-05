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
