package com.subhub.app.update;

import org.json.JSONArray;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class UpdateDownloadAttemptsTest {
    private static final String FIRST = "a".repeat(64);
    private static final String SECOND = "b".repeat(64);
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);

    @Test public void frequentChecksStopAfterThreeAutomaticStarts() throws Exception {
        String ledger = "[]";
        int starts = 0;
        for (int check = 0; check < 24 * 60; check++) {
            String next = UpdateDownloadAttempts.reserve(ledger, FIRST, NOW + TimeUnit.MINUTES.toMillis(check));
            if (next == null) continue;
            assertEquals(starts == 0 ? 0 : starts == 1 ? 60 : 420, check);
            ledger = next;
            starts++;
        }
        assertEquals(3, starts);
        assertNull(UpdateDownloadAttempts.reserve(ledger, FIRST, NOW + 7 * 24 * HOUR));
    }

    @Test public void switchingAssetsKeepsAnEarlierBackoff() throws Exception {
        String ledger = UpdateDownloadAttempts.reserve("[]", FIRST, NOW);
        assertNotNull(ledger);
        ledger = UpdateDownloadAttempts.reserve(ledger, SECOND, NOW + 1);
        assertNotNull(ledger);
        assertNull(UpdateDownloadAttempts.reserve(ledger, FIRST, NOW + HOUR - 1));
        assertNotNull(UpdateDownloadAttempts.reserve(ledger, FIRST, NOW + HOUR));
        assertNull(UpdateDownloadAttempts.reserve(ledger, SECOND, NOW - HOUR));
    }

    @Test public void onlyRecentAssetsAreRetained() throws Exception {
        String ledger = UpdateDownloadAttempts.reserve("[]", FIRST, NOW);
        for (int index = 0; index < 40; index++) {
            ledger = UpdateDownloadAttempts.reserve(ledger, String.format(java.util.Locale.ROOT, "%064x", index), NOW);
            assertNotNull(ledger);
        }
        assertEquals(32, new JSONArray(ledger).length());
    }
}
