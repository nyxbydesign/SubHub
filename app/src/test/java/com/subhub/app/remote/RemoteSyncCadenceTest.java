package com.subhub.app.remote;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RemoteSyncCadenceTest {
    @Test public void idleBackgroundChecksAreFifteenMinutesButForegroundKeepsPresenceFresh() {
        assertEquals(900_000, RemoteSyncCadence.delay(true, false, true, 0));
        assertEquals(30_000, RemoteSyncCadence.delay(false, false, true, 0));
    }
    @Test public void unreachableBackgroundPeerBacksOffToFifteenMinutes() {
        long[] expected = {30_000,60_000,120_000,240_000,480_000,900_000,900_000};
        for (int i=0;i<expected.length;i++) assertEquals(expected[i],RemoteSyncCadence.delay(true,false,false,i+1));
    }
    @Test public void boundedWindowsAndForegroundRetainFastRetries() {
        long[] expected = {2_000,4_000,8_000,16_000,32_000,60_000,60_000};
        for (int i=0;i<expected.length;i++) assertEquals(expected[i],RemoteSyncCadence.delay(false,false,false,i+1));
    }
    @Test public void PendingPairingNeverInheritsBackgroundIdleOrSlowRetryCadence() {
        for (boolean background:new boolean[]{false,true}) {
            assertEquals(2_000,RemoteSyncCadence.delay(background,true,true,6));
            assertEquals(2_000,RemoteSyncCadence.delay(background,true,false,1));
            assertEquals(60_000,RemoteSyncCadence.delay(background,true,false,6));
        }
    }
    @Test public void failureExponentIsBoundedAndSuccessIgnoresFailureCount() {
        assertEquals(30_000,RemoteSyncCadence.delay(true,false,false,0));
        assertEquals(900_000,RemoteSyncCadence.delay(true,false,false,Integer.MAX_VALUE));
        assertEquals(900_000,RemoteSyncCadence.delay(true,false,true,6));
    }

    @Test public void crossedDialRetryKeepsTheFirstSlotAtTheNormalFloorAndSeparatesPolling() {
        long dom = RemoteSyncCadence.crossedDialDelay(RemoteProtocol.Role.DOM, 1);
        long sub = RemoteSyncCadence.crossedDialDelay(RemoteProtocol.Role.SUB, 1);
        assertEquals(RemoteSyncCadence.delay(false, false, false, 1), dom);
        assertTrue("Separate the later slot even after one poll and maximum jitter", sub > dom + 2000 + 999);
        assertEquals(5500, sub);
        assertEquals(8000, RemoteSyncCadence.crossedDialDelay(RemoteProtocol.Role.DOM, 3));
        assertEquals(11500, RemoteSyncCadence.crossedDialDelay(RemoteProtocol.Role.SUB, 3));
        assertEquals(60000, RemoteSyncCadence.crossedDialDelay(RemoteProtocol.Role.SUB, 6));
    }
}
