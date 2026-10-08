package com.subhub.app.popup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PopupStormActivationPolicyTest {
    @Test public void connectedButIdleAccessibilityDoesNotStartStorm() {
        assertFalse(PopupStormActivationPolicy.shouldStart(false, false));
    }

    @Test public void eitherActiveProtectionRuntimeStartsStorm() {
        assertTrue(PopupStormActivationPolicy.shouldStart(true, false));
        assertTrue(PopupStormActivationPolicy.shouldStart(false, true));
    }
}
