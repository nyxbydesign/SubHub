package com.subhub.app.stats;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionHistoryCompatibilityTest {
    private static final String ID = "12345678-1234-1234-1234-123456789abc";
    private static final String V3 = "3,1000,20,4,5,6,7,8,9,10,11," + ID + ",Europe/Berlin";
    @Test public void legacyAndIdentifiedHistoryRemainReadableTogether() {
        List<StatsRepository.SessionEntry> entries = StatsRepository.parseSessionHistory(
                "1000,10,2;2,1000,15,3,4,5,6,7,8,9,10;" + V3);
        assertEquals(3, entries.size()); assertEquals("", entries.get(0).identity());
        assertEquals(15, entries.get(1).getDurationSeconds());
        assertEquals(ID, entries.get(2).identity()); assertEquals("Europe/Berlin", entries.get(2).zone());
        assertEquals(11, entries.get(2).getTamperEvents());
    }
    @Test public void malformedZoneAndIdentityCannotDiscardValidHistory() {
        List<StatsRepository.SessionEntry> entries = StatsRepository.parseSessionHistory(
                V3.replace("Europe/Berlin", "Invalid/Zone") + ";" + V3.replace(ID, "invalid") + ";" + V3);
        assertEquals(1, entries.size()); assertEquals(ID, entries.get(0).identity());
    }
}
