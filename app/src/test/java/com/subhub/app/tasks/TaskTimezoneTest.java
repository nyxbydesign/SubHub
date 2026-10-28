package com.subhub.app.tasks;

import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public final class TaskTimezoneTest {
    @Test public void targetZoneWinsOverAuthorDeviceAndMissingOrStaleMetadataCannotSchedule() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            long now = 1_000_000_000L;
            ZoneId sub = TaskTimezone.require("Asia/Tokyo", now - 10, now);
            assertEquals(ZoneId.of("Asia/Tokyo"), sub);
            assertNotEquals(ZoneId.systemDefault(), sub);
            assertThrows(IllegalStateException.class, () -> TaskTimezone.require("", now, now));
            assertThrows(IllegalStateException.class, () -> TaskTimezone.require("Europe/Berlin", 0, now));
            assertThrows(IllegalStateException.class, () -> TaskTimezone.require("Europe/Berlin", now + 1, now));
            assertThrows(IllegalStateException.class, () -> TaskTimezone.require("Europe/Berlin",
                    now - TaskTimezone.MAX_AGE_MILLIS - 1, now));
            assertEquals(ZoneId.of("Europe/Berlin"), TaskTimezone.require("Europe/Berlin",
                    now - TaskTimezone.MAX_AGE_MILLIS, now));
        } finally { TimeZone.setDefault(previous); }
    }

    @Test public void daylightTransitionsAndLaterTargetZoneChangesPreserveExistingOccurrences() throws Exception {
        ZoneId berlin = TaskTimezone.require("Europe/Berlin", 100, 100);
        TaskDefinition task = new TaskDefinition("dst-task", "Synthetic task", "",
                LocalDateTime.of(2026, 3, 22, 2, 30), berlin, 3_600_000,
                Set.of(DayOfWeek.SUNDAY), true, false, null);
        LocalDate gap = LocalDate.of(2026, 3, 29), overlap = LocalDate.of(2026, 10, 25);
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), task.start(gap));
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), task.start(overlap));
        assertEquals(3_600_000, Duration.between(task.start(overlap), task.end(overlap)).toMillis());
        String identity = task.occurrenceId(overlap);
        ZoneId changed = TaskTimezone.require("America/New_York", 200, 200);
        TaskDefinition retained = TaskDefinition.parse(task.json());
        assertEquals(identity, retained.occurrenceId(overlap));
        assertEquals(task.end(overlap), retained.end(overlap));
        TaskDefinition next = new TaskDefinition("new-zone-task", task.title, task.text,
                task.firstStart, changed, task.durationMillis, task.weekdays, true, false, null);
        assertNotEquals(retained.start(overlap), next.start(overlap));
        TaskEngine ledger = new TaskEngine();
        TaskAction create = new TaskAction("create-dst", task.id, TaskAction.Kind.CREATE, null,
                task.start(task.firstStart.toLocalDate()).toEpochMilli() - 1, "", "", List.of(), task);
        assertTrue(ledger.apply(create, true));
        assertFalse(ledger.apply(create, true));
        assertEquals(1, ledger.definitions().size());
        assertEquals(identity, ledger.occurrence(task.id, overlap, task.start(overlap).toEpochMilli()).id);
    }
}
