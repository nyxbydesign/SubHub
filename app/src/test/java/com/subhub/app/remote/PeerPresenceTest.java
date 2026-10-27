package com.subhub.app.remote;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PeerPresenceTest {
    @Test public void presenceUsesOnlyLiveOrRecentSuccessfulContact() {
        long now = 1_000_000L;
        assertTrue(PeerPresence.online(true, 0, now));
        assertTrue(PeerPresence.online(false, now - 90_000, now));
        assertFalse(PeerPresence.online(false, now - 90_001, now));
        assertFalse(PeerPresence.online(false, 0, now));
        assertFalse(PeerPresence.online(false, now + 1, now));
    }
}
