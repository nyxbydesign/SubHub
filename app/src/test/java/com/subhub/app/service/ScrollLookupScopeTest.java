package com.subhub.app.service;
import static org.junit.Assert.*;
import org.junit.Test;
public final class ScrollLookupScopeTest {
    private ScrollLookupScope provisional() { return new ScrollLookupScope(2, 3, 4, 7, 7, "app", true); }
    @Test public void firstConfirmedSameOwnerPreservesLaterDelta() {
        assertTrue(provisional().mayApply(2, 3, 7, "app", 5, true));
    }
    @Test public void otherOrUnknownOwnersCannotRebase() {
        assertFalse(provisional().mayApply(2, 3, 7, "app", 5, false));
    }
    @Test public void confirmedOwnerCannotRebase() {
        assertFalse(new ScrollLookupScope(2, 3, 4, 7, 7, "app", false).mayApply(2, 3, 7, "app", 5, true));
    }
    @Test public void confirmedAThenBThenACannotResurrectProvisionalA() {
        assertFalse(provisional().mayApply(2, 5, 7, "app", 7, true));
    }
    @Test public void actualOldWindowCannotBorrowActiveWindowStamp() {
        assertFalse(new ScrollLookupScope(2, 3, 4, 7, 6, "app", true).mayApply(2, 3, 7, "app", 4, true));
    }
    @Test public void packageCaptureAndInputFencesRemainStrict() {
        assertFalse(provisional().mayApply(3, 3, 7, "app", 4, true));
        assertFalse(provisional().mayApply(2, 4, 7, "app", 4, true));
        assertFalse(provisional().mayApply(2, 3, 7, "other", 4, true));
    }
}
