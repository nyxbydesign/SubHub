package com.subhub.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ForegroundLimitHandoffTest {
    @Test public void aSpentBudgetSuppressesEnabledEffectsEvenWhenHomeCannotBeRequested() {
        ForegroundLimitHandoff state = new ForegroundLimitHandoff();
        state.observed("com.example.selected"); state.budgetReached(true);
        assertFalse(state.effectsAllowed());
        assertTrue("An undismissed app still contributes actual usage", state.accountingAllowed());
    }

    @Test public void staleRootsCannotResumeEffectsOrAccountingWhileHomeIsPending() {
        ForegroundLimitHandoff state = new ForegroundLimitHandoff();
        state.observed("com.example.selected"); state.budgetReached(true); state.homeRequested();
        state.observed("com.example.selected"); state.budgetReached(true);
        assertFalse(state.effectsAllowed()); assertFalse(state.accountingAllowed());
        state.observed("com.example.launcher");
        assertTrue(state.effectsAllowed()); assertTrue(state.accountingAllowed());
        state.observed("com.example.selected"); state.budgetReached(true);
        assertFalse(state.effectsAllowed()); assertTrue(state.accountingAllowed());
    }

    @Test public void releasingTheBudgetResumesTheSameObservedAppWithoutInventingAnExit() {
        ForegroundLimitHandoff state = new ForegroundLimitHandoff();
        state.observed("com.example.selected"); state.budgetReached(true); state.homeRequested();
        state.budgetReached(false); state.observed("com.example.selected");
        assertTrue(state.effectsAllowed()); assertTrue(state.accountingAllowed());
    }

    @Test public void anUnrelatedHomeRequestDoesNotSuspendParticipation() {
        ForegroundLimitHandoff state = new ForegroundLimitHandoff();
        state.observed("com.example.selected"); state.homeRequested();
        assertTrue(state.effectsAllowed()); assertTrue(state.accountingAllowed());
    }
}
