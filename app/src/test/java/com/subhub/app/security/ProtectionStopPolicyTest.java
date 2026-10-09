package com.subhub.app.security;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ProtectionStopPolicyTest {
    @Test public void permanentServiceRequiresDomWithoutHardcoreToo() {
        assertEquals(ProtectionStopPolicy.Decision.REQUIRE_CONTROLLER,
                ProtectionStopPolicy.evaluate(false, false, false, false));
        assertEquals(ProtectionStopPolicy.Decision.ALLOW,
                ProtectionStopPolicy.evaluate(false, false, true, false));
    }

    @Test public void hardcoreRequiresControllerUntilDomModeIsUnlocked() {
        assertEquals(ProtectionStopPolicy.Decision.REQUIRE_CONTROLLER,
                ProtectionStopPolicy.evaluate(false, true, false, false));
        assertEquals(ProtectionStopPolicy.Decision.ALLOW,
                ProtectionStopPolicy.evaluate(false, true, true, false));
    }

    @Test public void timedServiceBindsSubSpaceButDomMayReleaseIt() {
        assertEquals(ProtectionStopPolicy.Decision.TIMER_LOCKED,
                ProtectionStopPolicy.evaluate(true, false, false, false));
        assertEquals(ProtectionStopPolicy.Decision.ALLOW,
                ProtectionStopPolicy.evaluate(true, true, true, false));
    }

    @Test public void paidPauseIsAnIntentionalStopException() {
        assertEquals(ProtectionStopPolicy.Decision.ALLOW,
                ProtectionStopPolicy.evaluate(true, true, false, true));
    }
}
