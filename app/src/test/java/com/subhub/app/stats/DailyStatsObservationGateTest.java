package com.subhub.app.stats;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DailyStatsObservationGateTest {
    @Test public void slowSameObservationDoesNotRequestReplacementBeforeCompletion(){
        DailyStatsObservationGate gate=new DailyStatsObservationGate();Object key=new Object();assertTrue(gate.changed(key));
        gate.requested(key);assertFalse(gate.changed(key));assertTrue(gate.accepts(key,key,true));
    }
    @Test public void lateResultFromOldSnapshotCannotPublishAfterNewAck(){
        DailyStatsObservationGate gate=new DailyStatsObservationGate();Object first=new Object(),later=new Object();gate.requested(first);
        assertTrue(gate.changed(later));gate.requested(later);assertFalse(gate.accepts(first,later,true));assertTrue(gate.accepts(later,later,true));
    }
    @Test public void revocationRefusesEvenIdenticalObservationAndNullUnavailableKeys(){
        DailyStatsObservationGate gate=new DailyStatsObservationGate();Object key=new Object();gate.requested(key);
        assertFalse(gate.accepts(key,key,false));assertTrue(gate.changed(null));gate.requested(null);
        assertFalse(gate.accepts(null,null,false));assertFalse(gate.changed(null));
    }
}
