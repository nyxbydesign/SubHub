package com.subhub.app.stats;

import java.time.*;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DailyTimeSlicesTest {
    @Test public void splitsAtLocalMidnight() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        long start = LocalDateTime.of(2026,10,8,23,59,50).atZone(zone).toInstant().toEpochMilli();
        assertEquals(Map.of("2026-10-08",10000L,"2026-10-09",20000L),DailyTimeSlices.split(start,30000,zone));
    }
    @Test public void daylightSavingDaysUseRealElapsedTime() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        LocalDate spring = LocalDate.of(2026,3,29), autumn = LocalDate.of(2026,10,25);
        assertEquals(Map.of(spring.toString(),23*3600000L),DailyTimeSlices.split(spring.atStartOfDay(zone).toInstant().toEpochMilli(),23*3600000L,zone));
        assertEquals(Map.of(autumn.toString(),25*3600000L),DailyTimeSlices.split(autumn.atStartOfDay(zone).toInstant().toEpochMilli(),25*3600000L,zone));
    }
    @Test public void zeroElapsedProducesNoHistory() { assertTrue(DailyTimeSlices.split(0,0,ZoneOffset.UTC).isEmpty()); }
}
