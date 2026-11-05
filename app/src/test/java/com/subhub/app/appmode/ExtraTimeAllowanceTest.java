package com.subhub.app.appmode;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExtraTimeAllowanceTest {
    private static final long MINUTE = 60_000L;
    @Test public void independentAppCreditsDoNotShareTheirAllowance() {
        ExtraTimeAllowance appA = new ExtraTimeAllowance(10*MINUTE,15*MINUTE);
        ExtraTimeAllowance appB = new ExtraTimeAllowance(10*MINUTE,20*MINUTE);
        assertEquals(10*MINUTE,appA.remaining(5*MINUTE,30*MINUTE,20*MINUTE,20*MINUTE,true,true));
        assertEquals(10*MINUTE,appB.remaining(10*MINUTE,30*MINUTE,30*MINUTE,20*MINUTE,true,true));
        assertFalse(ExtraTimeAllowance.NONE.permitsSharedException(0));
        assertEquals(0,ExtraTimeAllowance.NONE.remaining(0,30*MINUTE,40*MINUTE,20*MINUTE,true,true));
        assertFalse(appA.permitsSharedException(15*MINUTE));
        assertFalse(appB.permitsSharedException(20*MINUTE));
    }
    @Test public void individualOnlyCreditDoesNotChangeRecurringBudget() {
        ExtraTimeAllowance credit = new ExtraTimeAllowance(15*MINUTE,45*MINUTE);
        assertEquals(45*MINUTE,credit.individualBudget(30*MINUTE));
        assertEquals(15*MINUTE,credit.remaining(30*MINUTE,30*MINUTE,30*MINUTE,120*MINUTE,true,false));
        assertEquals(30*MINUTE,ExtraTimeAllowance.NONE.individualBudget(30*MINUTE));
    }
    @Test public void combinedBudgetCanExpireMidwayThroughApprovedAppTime() {
        ExtraTimeAllowance credit = new ExtraTimeAllowance(10*MINUTE,40*MINUTE);
        assertEquals(10*MINUTE,credit.remaining(30*MINUTE,30*MINUTE,115*MINUTE,120*MINUTE,true,true));
        assertEquals(5*MINUTE,credit.remaining(35*MINUTE,30*MINUTE,120*MINUTE,120*MINUTE,true,true));
        assertEquals(0,credit.remaining(40*MINUTE,30*MINUTE,125*MINUTE,120*MINUTE,true,true));
    }
    @Test public void budgetsRemainBoundedToOneDay() {
        ExtraTimeAllowance credit = new ExtraTimeAllowance(86_400_000,86_400_000);
        assertEquals(86_400_000,credit.individualBudget(86_400_000));
        assertEquals(0,credit.remaining(86_400_000,86_400_000,86_400_000,86_400_000,true,true));
    }
}
