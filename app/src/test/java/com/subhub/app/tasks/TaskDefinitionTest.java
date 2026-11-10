package com.subhub.app.tasks;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.*;

public class TaskDefinitionTest {
    private static TaskDefinition task(LocalDateTime start, long duration, Set<DayOfWeek> days) {
        return new TaskDefinition("berlin-task", "Task", "Instructions", start,
                ZoneId.of("Europe/Berlin"), duration, days, true, true, null);
    }

    @Test public void springGapShiftsWallClockForwardWithoutChangingDateIdentity() throws Exception {
        TaskDefinition task = task(LocalDateTime.of(2026, 3, 28, 2, 30), 3_600_000,
                EnumSet.allOf(DayOfWeek.class));
        LocalDate gap = LocalDate.of(2026, 3, 29);
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), task.start(gap));
        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30),
                task.start(gap).atZone(task.zone).toLocalDateTime());
        assertEquals("berlin-task:2026-03-29", task.occurrenceId(gap));
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), task.start(gap.plusDays(1)));
        assertEquals(Duration.ofHours(23), Duration.between(task.start(gap), task.start(gap.plusDays(1))));
        TaskDefinition replay = TaskDefinition.parse(new JSONObject(task.json().toString()));
        assertEquals(task.start(gap), replay.start(gap));
        assertEquals(task.occurrenceId(gap), replay.occurrenceId(gap));
        assertEquals(3_600_000, Duration.between(task.start(gap), task.end(gap)).toMillis());
    }

    @Test public void springWindowUsesElapsedDurationAcrossClockJump() {
        TaskDefinition task = task(LocalDateTime.of(2026, 3, 29, 1, 30), 7_200_000,
                EnumSet.allOf(DayOfWeek.class));
        LocalDate date = task.firstStart.toLocalDate();
        assertEquals(Instant.parse("2026-03-29T00:30:00Z"), task.start(date));
        assertEquals(Instant.parse("2026-03-29T02:30:00Z"), task.end(date));
        assertEquals(LocalDateTime.of(2026, 3, 29, 4, 30),
                task.end(date).atZone(task.zone).toLocalDateTime());
        assertEquals(Duration.ofHours(2), Duration.between(task.start(date), task.end(date)));
    }

    @Test public void autumnOverlapUsesEarlierOffsetOnceAndElapsedDuration() throws Exception {
        TaskDefinition task = task(LocalDateTime.of(2026, 10, 24, 2, 30), 3_600_000,
                EnumSet.allOf(DayOfWeek.class));
        LocalDate overlap = LocalDate.of(2026, 10, 25);
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), task.start(overlap));
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), task.end(overlap));
        assertEquals(task.start(overlap).atZone(task.zone).toLocalDateTime(),
                task.end(overlap).atZone(task.zone).toLocalDateTime());
        assertEquals(Duration.ofHours(1), Duration.between(task.start(overlap), task.end(overlap)));
        assertEquals("berlin-task:2026-10-25", task.occurrenceId(overlap));
        assertEquals(overlap, task.nextDate(overlap));
        assertEquals(overlap.plusDays(1), task.nextDate(overlap.plusDays(1)));
        assertEquals(Duration.ofHours(25), Duration.between(task.start(overlap), task.start(overlap.plusDays(1))));
        TaskDefinition replay = TaskDefinition.parse(new JSONObject(task.json().toString()));
        assertEquals(task.start(overlap), replay.start(overlap));
        assertEquals(task.occurrenceId(overlap), replay.occurrenceId(overlap));
    }

    @Test public void eachIsoWeekdaySelectsOnlyThatDayFromMondayThroughSunday() throws Exception {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        for (DayOfWeek selected : DayOfWeek.values()) {
            TaskDefinition task = task(monday.atTime(9, 0), 60_000, EnumSet.of(selected));
            assertTrue(task.recurring());
            LocalDate first = monday.plusDays(selected.getValue() - 1);
            assertEquals(first, task.nextDate(monday.minusDays(10)));
            assertEquals(first.plusWeeks(1), task.nextDate(first.plusDays(1)));
            assertEquals(selected.getValue(), task.json().getJSONArray("weekdays").getInt(0));
            for (int day = 0; day < 7; day++) {
                LocalDate date = monday.plusDays(day);
                assertEquals(selected == date.getDayOfWeek(), task.occursOn(date));
                if (!task.occursOn(date)) {
                    assertThrows(IllegalArgumentException.class, () -> task.start(date));
                    assertThrows(IllegalArgumentException.class, () -> task.occurrenceId(date));
                }
            }
            assertFalse(task.occursOn(first.minusWeeks(1)));
        }
    }

    @Test public void emptyWeekdaySelectionIsOneoffAndNeverReopens() {
        TaskDefinition task = task(LocalDateTime.of(2026, 10, 25, 2, 30), 60_000,
                Collections.emptySet());
        LocalDate date = task.firstStart.toLocalDate();
        assertFalse(task.recurring());
        assertTrue(task.occursOn(date));
        assertFalse(task.occursOn(date.minusDays(1)));
        assertFalse(task.occursOn(date.plusWeeks(1)));
        assertEquals(date, task.nextDate(date.minusYears(1)));
        assertEquals(date, task.nextDate(date));
        assertNull(task.nextDate(date.plusDays(1)));
    }

    @Test public void weekdayParserRejectsDuplicatesOutOfRangeAndCoercedScalars() throws Exception {
        TaskDefinition task = task(LocalDateTime.of(2026, 10, 19, 9, 0), 60_000,
                EnumSet.allOf(DayOfWeek.class));
        for (Object[] invalid : new Object[][] {{1, 1}, {0}, {8}, {-1}, {"1"},
                {1.0}, {true}, {JSONObject.NULL}, {1, 2, 3, 4, 5, 6, 7, 1}}) {
            JSONObject value = new JSONObject(task.json().toString());
            value.put("weekdays", new JSONArray(invalid));
            assertThrows("Invalid weekdays " + value, RuntimeException.class,
                    () -> TaskDefinition.parse(value));
        }
    }

    @Test public void weekdayParserRejectsLongValuesThatWouldWrapToValidIsoDays() throws Exception {
        JSONObject value = task(LocalDateTime.of(2026, 10, 19, 9, 0), 60_000,
                EnumSet.of(DayOfWeek.MONDAY)).json();
        value.put("weekdays", new JSONArray().put(4_294_967_297L));
        assertThrows(RuntimeException.class, () -> TaskDefinition.parse(value));
    }
}
