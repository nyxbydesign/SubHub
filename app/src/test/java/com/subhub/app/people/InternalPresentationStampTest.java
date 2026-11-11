package com.subhub.app.people;

import com.subhub.app.internal.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class InternalPresentationStampTest {
    private InternalSubRecord record(String generation,long profile,long config,boolean visible)throws Exception{
        java.lang.reflect.Constructor<InternalSubRecord> constructor=InternalSubRecord.class.getDeclaredConstructor(String.class,String.class,long.class,long.class,boolean.class,long.class,InternalProfile.class,Map.class);
        constructor.setAccessible(true);return constructor.newInstance(InternalStorageScope.TARGET_ID,generation,profile,config,visible,1L,new InternalProfile("Owned","","","",0),Map.of());
    }
    @Test public void ownerAckChangesInvalidateEvenWhenProfileAndConfigurationDoNot()throws Exception{
        InternalSubRecord r=record(UUID.randomUUID().toString(),4,2,true);InternalPresentationStamp stamp=new InternalPresentationStamp(r,9,1);
        assertTrue(stamp.current(r,9,1));assertFalse(stamp.current(r,10,1));assertFalse(stamp.current(r,-1,1));
    }
    @Test public void retiredGenerationControllerLockAndProfileEditInvalidate()throws Exception{
        String generation=UUID.randomUUID().toString();InternalSubRecord r=record(generation,4,2,true);InternalPresentationStamp stamp=new InternalPresentationStamp(r,9,1);
        assertFalse(stamp.current(record(UUID.randomUUID().toString(),4,2,true),9,1));
        assertFalse(stamp.current(record(generation,4,2,false),9,1));assertFalse(stamp.current(r,9,2));
        assertFalse(stamp.current(record(generation,5,2,true),9,1));assertFalse(stamp.current(record(generation,5,3,true),9,1));
    }
    @Test public void oldCompletionCannotReplaceLaterAckOrKnownAvailability()throws Exception{
        InternalSubRecord r=record(UUID.randomUUID().toString(),4,2,true);
        InternalPresentationStamp old=new InternalPresentationStamp(r,9,1),later=new InternalPresentationStamp(r,10,1),unknown=new InternalPresentationStamp(r,-1,1);
        assertTrue(later.current(r,10,1));assertFalse(old.current(r,10,1));assertFalse(unknown.current(r,10,1));
        assertTrue(unknown.current(r,-1,1));assertFalse(unknown.current(null,-1,1));
    }
    private com.subhub.app.stats.OwnerObservation at(String instant,String zone){
        return new com.subhub.app.stats.OwnerObservation(java.time.Instant.parse(instant).toEpochMilli(),zone);
    }
    @Test public void midnightInvalidatesWithoutAnyOwnerOrProfileWrite()throws Exception{
        InternalSubRecord r=record(UUID.randomUUID().toString(),4,2,true);
        InternalPresentationStamp stamp=new InternalPresentationStamp(r,9,1,at("2026-11-10T23:59:59Z","UTC"));
        assertTrue(stamp.current(r,9,1,at("2026-11-10T23:59:59.999Z","UTC")));
        assertFalse(stamp.current(r,9,1,at("2026-11-11T00:00:01Z","UTC")));
    }
    @Test public void travelInvalidatesBothChangedAndUnchangedCivilDates()throws Exception{
        InternalSubRecord r=record(UUID.randomUUID().toString(),4,2,true);
        InternalPresentationStamp stamp=new InternalPresentationStamp(r,9,1,at("2026-11-10T23:59:59Z","UTC"));
        assertFalse(stamp.current(r,9,1,at("2026-11-10T23:59:59Z","Asia/Tokyo")));
        assertFalse(stamp.current(r,9,1,at("2026-11-10T23:59:59Z","America/New_York")));
    }
    @Test public void delayedOldCalendarCannotPublishOverNewCalendarAtSameRevision()throws Exception{
        InternalSubRecord r=record(UUID.randomUUID().toString(),4,2,true);
        var before=at("2026-11-10T23:59:59Z","UTC");var after=at("2026-11-11T00:00:01Z","UTC");
        InternalPresentationStamp old=new InternalPresentationStamp(r,9,1,before),fresh=new InternalPresentationStamp(r,9,1,after);
        assertFalse(old.current(r,9,1,after));assertTrue(fresh.current(r,9,1,after));
        assertFalse(fresh.current(r,9,1,before));
    }
    @Test public void displayedDayUsesCapturedClockAndZoneNotHostCalendar(){
        var observed=at("2026-12-31T23:30:00Z","Asia/Tokyo");
        assertEquals(java.time.LocalDate.of(2027,1,1),InternalPresentationCalendar.day(observed));
        assertFalse(InternalPresentationCalendar.current(observed,at("2026-12-31T23:30:00Z","UTC")));
    }

}
