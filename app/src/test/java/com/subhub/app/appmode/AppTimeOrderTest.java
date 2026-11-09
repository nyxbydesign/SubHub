package com.subhub.app.appmode;

import static org.junit.Assert.*;
import org.junit.Test;

public final class AppTimeOrderTest {
    @Test public void percentageNotAbsoluteMinutesDeterminesOrder() {
        // 2/60 remaining goes before 1/10 remaining, even though two minutes is more than one.
        assertTrue(AppTimeOrder.compare(58,60,"Z","z",9,10,"A","a") < 0);
    }
    @Test public void exhaustedAndOverusedComeFirstUnknownAndDisabledComeLast() {
        assertEquals(0d,AppTimeOrder.remainingFraction(100,10),0d);
        assertTrue(AppTimeOrder.compare(10,10,"A","a",0,10,"B","b") < 0);
        assertTrue(AppTimeOrder.compare(0,10,"A","a",-1,10,"B","b") < 0);
        assertTrue(AppTimeOrder.compare(0,10,"A","a",0,0,"B","b") < 0);
    }
    @Test public void equalFractionsUseLabelThenStablePackageIdentity() {
        assertTrue(AppTimeOrder.compare(5,10,"Alpha","z",50,100,"beta","a") < 0);
        assertTrue(AppTimeOrder.compare(5,10,"Same","a",50,100,"same","b") < 0);
        assertEquals(0,AppTimeOrder.compare(5,10,"Same","a",50,100,"Same","a"));
    }
    @Test public void noIntegerOverflowOrRoundingToWholePercent() {
        assertEquals(0d,AppTimeOrder.remainingFraction(Long.MAX_VALUE,86_400_000L),0d);
        assertTrue(AppTimeOrder.compare(9_999,10_000,"Z","z",999,1000,"A","a") < 0);
        assertEquals(1d,AppTimeOrder.remainingFraction(0,Long.MAX_VALUE),0d);
    }
    @Test public void appliedBonusChangesActualPercentageWithoutChangingBaseLimit() {
        ExtraTimeAllowance grant=new ExtraTimeAllowance(15*60_000L,0);
        long effective=grant.individualBudget(5*60_000L);
        assertEquals(20*60_000L,effective);
        assertEquals(0.75d,AppTimeOrder.remainingFraction(5*60_000L,effective),0d);
        assertTrue(AppTimeOrder.compare(5*60_000L,effective,"A","a",5*60_000L,10*60_000L,"B","b") > 0);
    }
    @Test public void sharedExceptionCeilingAndDailyCapAreRespected() {
        ExtraTimeAllowance grant=new ExtraTimeAllowance(15*60_000L,80*60_000L);
        assertEquals(80*60_000L,grant.individualBudget(5*60_000L));
        assertEquals(10*60_000L,grant.remaining(70*60_000L,5*60_000L,100*60_000L,60*60_000L,true,true));
        assertEquals(86_400_000L,new ExtraTimeAllowance(86_400_000L,0).individualBudget(86_400_000L));
    }
    @Test public void remoteBonusRequiresMatchingBoundedSourceObservation() {
        assertEquals(1_200_000L,AppTimeOrder.confirmedBudget(300_000L,300_000L,1_200_000L));
        assertEquals(300_000L,AppTimeOrder.confirmedBudget(300_000L,600_000L,1_200_000L));
        assertEquals(300_000L,AppTimeOrder.confirmedBudget(300_000L,300_000L,Long.MAX_VALUE));
        assertEquals(300_000L,AppTimeOrder.confirmedBudget(300_000L,300_000L,-1));
    }
}
