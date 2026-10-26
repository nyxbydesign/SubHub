package com.subhub.app.people;

import com.subhub.app.security.ControllerPinManager;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ConfigurationAuthorityTest {
    @After public void reset() {
        People.installRemoteAuthority(() -> false);
        ControllerPinManager.enterSubMode();
    }

    @Test public void localAuthenticationCannotOverrideRemoteConfigurationAuthority() {
        People.installRemoteAuthority(() -> true);
        ControllerPinManager.enterDomMode();
        assertTrue(ControllerPinManager.isDomModeActive());
        assertFalse(ControllerPinManager.canEditConfiguration());
        assertFalse(PersonRoutes.canEditLocal(null, PersonId.LOCAL));
    }

    @Test public void authorityReadFailureDeniesConfigurationAccess() {
        People.installRemoteAuthority(() -> { throw new IllegalStateException("Store unavailable"); });
        ControllerPinManager.enterDomMode();
        assertFalse(ControllerPinManager.canEditConfiguration());
    }

    @Test public void unpairedLocalEditingRequiresDomAndTheLocalIdentity() {
        People.installRemoteAuthority(() -> false);
        ControllerPinManager.enterSubMode();
        assertFalse(PersonRoutes.canEditLocal(null, PersonId.LOCAL));
        ControllerPinManager.enterDomMode();
        assertTrue(PersonRoutes.canEditLocal(null, PersonId.LOCAL));
        assertFalse(PersonRoutes.canEditLocal(null, new PersonId("peer:alex")));
        assertFalse(PersonRoutes.canEditLocal(null, null));
    }
}
