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
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;

/** Failures in diagnostic assertions must not retain live adapter redirects or owned resources. */
public final class DummyPreviewEndpointFixtureAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Map<String, ?> settings, controllerAuth;
    private boolean wasDom;

    @Before public void captureAuthorization() {
        wasDom = ControllerPinManager.isDomModeActive();
        settings = new LinkedHashMap<>(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll());
        controllerAuth = new LinkedHashMap<>(context.getSharedPreferences("subhub_controller_auth", 0).getAll());
        // These contracts exercise owned adapter cleanup, not authenticated mutation commands.
        // The normal constructor's snapshot uses observeRead/requireRead for the current role.
    }
    @After public void restoreAuthorization() throws Throwable {
        Throwable failure = null;
        try {
            if (settings != null) assertTrue("Cleanup preserves original settings and role preferences",
                    settings.equals(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll()));
            if (controllerAuth != null) assertTrue("Cleanup preserves original controller authentication preferences",
                    controllerAuth.equals(context.getSharedPreferences("subhub_controller_auth", 0).getAll()));
            assertEquals("Cleanup preserves the original Dom session", wasDom, ControllerPinManager.isDomModeActive());
        } catch (Throwable assertion) {
            failure = assertion;
        } finally {
            // Restore unexpected changes without replacing the original assertion or skipping
            // later restoration if a preference restore itself fails. Never rewind revisions.
            try {
                if (settings != null && !settings.equals(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getAll()))
                    SharedPreferenceTestRestore.restore(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0), settings);
            } catch (Throwable restore) { if (failure == null) failure = restore; else failure.addSuppressed(restore); }
            try {
                if (controllerAuth != null && !controllerAuth.equals(context.getSharedPreferences("subhub_controller_auth", 0).getAll()))
                    SharedPreferenceTestRestore.restore(context.getSharedPreferences("subhub_controller_auth", 0), controllerAuth);
            } catch (Throwable restore) { if (failure == null) failure = restore; else failure.addSuppressed(restore); }
            try {
                if (wasDom != ControllerPinManager.isDomModeActive()) {
                    if (wasDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
                }
            } catch (Throwable restore) { if (failure == null) failure = restore; else failure.addSuppressed(restore); }
        }
        if (failure != null) throw failure;
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
