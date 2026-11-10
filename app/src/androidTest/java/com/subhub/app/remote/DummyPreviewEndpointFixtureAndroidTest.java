package com.subhub.app.remote;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import java.util.LinkedHashMap;
import java.util.Map;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

/** Failures in diagnostic assertions must not retain live adapter redirects or owned resources. */
public final class DummyPreviewEndpointFixtureAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> settings;
    private boolean wasDom;

    @Before public void authorizeOwnedGuest() {
        settings = new LinkedHashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll());
        wasDom = ControllerPinManager.isDomModeActive();
        assertTrue("Verify the runner's existing fixture PIN before changing role capabilities",
                ControllerPinManager.verify(context, "2468"));
        assertTrue("Use the guarded device-role transition", DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
    }
    @After public void restoreAuthorization() {
        try {
            if (settings != null) SharedPreferenceTestRestore.restore(
                    context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0), settings);
        } finally {
            if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        }
    }

    @Test public void constructorFailureRestoresRealAdaptersAndDeletesOwnedStore() throws Exception {
        AtomicReference<DummyPreviewEndpointFixture> constructed = new AtomicReference<>();
        AssertionError failure = assertThrows(AssertionError.class, () ->
                new DummyPreviewEndpointFixture(context, fixture -> {
                    constructed.set(fixture);
                    throw new AssertionError("Owned constructor observation failed");
                }));
        assertEquals("Owned constructor observation failed", failure.getMessage());
        assertEquals("Cleanup must succeed despite constructor assertion", 0, failure.getSuppressed().length);
        assertNotNull(constructed.get());
        constructed.get().assertOriginalRestored();
        constructed.get().assertOwnedDisposed();
    }

    @Test public void failedCloseWitnessStillRestoresRealAdaptersAndDeletesOwnedStore() throws Exception {
        try (DummyPreviewEndpointFixture fixture = new DummyPreviewEndpointFixture(context)) {
            AssertionError failure = assertThrows(AssertionError.class, () -> fixture.closeWithWitness(() -> {
                throw new AssertionError("Owned cleanup observation failed");
            }));
            assertEquals("Owned cleanup observation failed", failure.getMessage());
            assertEquals("Cleanup must succeed despite witness assertion", 0, failure.getSuppressed().length);
            fixture.assertOriginalRestored();
            fixture.assertOwnedDisposed();
        }
    }
}
