package com.subhub.app.popup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exercises the real manager timer after its requesting editor has been destroyed. */
@RunWith(AndroidJUnit4.class)
public final class PopupPreviewLifetimeAndroidTest {
    @Test public void expiryKeepsEditorOnNaturalHomeAtmosphereBackStack() {
        PopupStormManager manager = PopupStormManager.get();
        android.content.Intent intent = new android.content.Intent(context, com.subhub.app.MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .putExtra(com.subhub.app.MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        try (ActivityScenario<com.subhub.app.MainActivity> home = ActivityScenario.launch(intent)) {
            home.onActivity(activity -> activity.startActivity(new android.content.Intent(activity,
                    com.subhub.app.atmosphere.AtmosphereActivity.class)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            AtomicReference<PopupStormActivity> editor = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof com.subhub.app.atmosphere.AtmosphereActivity) {
                        activity.findViewById(R.id.button_popup_storm).performClick();
                        break;
                    }
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof PopupStormActivity) editor.set((PopupStormActivity) activity);
                }
                org.junit.Assert.assertNotNull("Natural navigation must reach the popup editor", editor.get());
                editor.get().findViewById(R.id.button_preview).performClick();
            });
            await(manager::isRunning, 3_000);
            await(() -> !manager.isPreviewing(), manager.remainingPreviewMillis() + 2_000);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertTrue("Expiry must not surface the underlying Home activity",
                        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).contains(editor.get()));
                editor.get().finish();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                        .getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (activity instanceof com.subhub.app.atmosphere.AtmosphereActivity) activity.finish();
                }
            });
        }
    }
    @Test public void previewExpiryLeavesItsEditorResumedInsteadOfReturningHome() {
        PopupStormManager manager = PopupStormManager.get();
        try (ActivityScenario<PopupStormActivity> scenario = ActivityScenario.launch(PopupStormActivity.class)) {
            AtomicReference<PopupStormActivity> editor = new AtomicReference<>();
            scenario.onActivity(activity -> {
                editor.set(activity);
                activity.findViewById(R.id.button_preview).performClick();
                assertTrue(manager.isPreviewing());
            });
            await(manager::isRunning, 3_000);
            await(() -> !manager.isPreviewing(), manager.remainingPreviewMillis() + 2_000);
            assertFalse(manager.isRunning());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(editor.get(), activity);
                assertFalse(activity.isFinishing());
                assertTrue(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).contains(activity));
            });
        }
    }
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> original;
    private boolean originallyArmed;
    private boolean originallyDom;
    private boolean overlayChanged;
    private String overlayMode;

    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        if (!ControllerPinManager.isConfigured(context)) {
            assertTrue(ControllerPinManager.setPin(context, "2468"));
        }
        preferences = PopupStormSettings.preferences(context);
        original = preferences.getAll();
        originallyArmed = new AppModeManager(context).isArmed();
        originallyDom = ControllerPinManager.isDomModeActive();
        new AppModeManager(context).setArmed(false);
        ControllerPinManager.enterDomMode();
        PopupStormManager.get().stop();
        if (!Settings.canDrawOverlays(context)) {
            String current = shell("appops get " + context.getPackageName()
                    + " SYSTEM_ALERT_WINDOW");
            Matcher mode = Pattern.compile("SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)")
                    .matcher(current);
            if (mode.find()) overlayMode = mode.group(1);
            else if (current.contains("No operations")) overlayMode = "default";
            else throw new IllegalStateException("Unknown overlay test permission state");
            overlayChanged = true;
            shell("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            assertTrue("Overlay permission fixture must be ready", Settings.canDrawOverlays(context));
        }
        // Popup settings share the main preference file; never erase the PIN fixture.
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, true)
                .putBoolean(PopupStormSettings.K_ACK, true).commit();
    }

    @After public void tearDown() throws Exception {
        PopupStormManager.get().stop();
        if (preferences != null && original != null) {
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) restore.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) restore.putLong(entry.getKey(), (Long) value);
                else if (value instanceof Float) restore.putFloat(entry.getKey(), (Float) value);
                else if (value instanceof String) restore.putString(entry.getKey(), (String) value);
                else if (value instanceof Set) {
                    @SuppressWarnings("unchecked") Set<String> values = (Set<String>) value;
                    restore.putStringSet(entry.getKey(), values);
                }
            }
            restore.commit();
        }
        if (overlayChanged) shell("appops set " + context.getPackageName()
                + " SYSTEM_ALERT_WINDOW " + overlayMode);
        if (context != null) new AppModeManager(context).setArmed(originallyArmed);
        if (originallyDom) ControllerPinManager.enterDomMode();
        else ControllerPinManager.enterSubMode();
    }

    @Test public void previewOutlivesEditorAndExpiresWithoutEnteringService() {
        PopupStormManager manager = PopupStormManager.get();
        boolean pinConfigured = ControllerPinManager.isConfigured(context);
        AtomicReference<PopupStormManager.PreviewResult> result = new AtomicReference<>();
        try (ActivityScenario<PopupStormActivity> scenario =
                     ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> result.set(manager.preview(activity)));
            assertEquals(PopupStormManager.PreviewResult.STARTED, result.get());
            await(manager::isRunning, 3_000);
            assertTrue(manager.isPreviewing());
            assertFalse(new AppModeManager(context).isArmed());
        }
        assertTrue("Destroying the editor must leave the manager-owned deadline intact",
                manager.isPreviewing());
        await(() -> !manager.isPreviewing(), manager.remainingPreviewMillis() + 2_000);
        assertFalse(manager.isRunning());
        assertFalse(new AppModeManager(context).isArmed());
        assertEquals(pinConfigured, ControllerPinManager.isConfigured(context));
    }

    @Test public void previewRequiresThePhotosensitivityAcknowledgement() {
        preferences.edit().putBoolean(PopupStormSettings.K_ACK, false).commit();
        AtomicReference<PopupStormManager.PreviewResult> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(PopupStormManager.get().preview(context)));
        assertEquals(PopupStormManager.PreviewResult.UNAVAILABLE, result.get());
        assertFalse(PopupStormManager.get().isPreviewing());
        assertFalse(new AppModeManager(context).isArmed());
    }

    @Test public void disabledStormPreviewsFromTheButtonSurvivesReloadAndExpiresWithoutEnablingService() {
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false).commit();
        PopupStormManager manager = PopupStormManager.get();
        try (ActivityScenario<PopupStormActivity> scenario = ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> {
                activity.findViewById(R.id.button_preview).performClick();
                assertTrue(manager.isPreviewing());
                assertFalse(PopupStormSettings.load(context).isEnabled());
            });
            await(manager::isRunning, 3_000);
            scenario.onActivity(activity -> {
                manager.reloadSettings(activity);
                assertTrue(manager.isRunning());
                assertFalse("Live service must still reject disabled Storm", manager.canStart(activity));
            });
            SystemClock.sleep(300); // Prove multiple rendering frames survive, not just startup.
            assertTrue(manager.isRunning());
            assertFalse(new AppModeManager(context).isArmed());
        }
        await(() -> !manager.isPreviewing(), manager.remainingPreviewMillis() + 2_000);
        assertFalse(manager.isRunning());
        assertFalse(PopupStormSettings.load(context).isEnabled());
        assertFalse(new AppModeManager(context).isArmed());
    }

    @Test public void previewWarningCanBeCancelledWithoutEnablingOrAcknowledgingStorm() {
        preferences.edit().putBoolean(PopupStormSettings.K_ENABLED, false)
                .putBoolean(PopupStormSettings.K_ACK, false).commit();
        try (ActivityScenario<PopupStormActivity> scenario = ActivityScenario.launch(PopupStormActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(R.id.button_preview).performClick());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button2))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertFalse(PopupStormManager.get().isPreviewing());
            assertFalse(PopupStormSettings.load(context).isEnabled());
            assertFalse(PopupStormSettings.load(context).isAcknowledged());
        }
    }

    private static void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(40);
        }
        assertTrue("Popup preview lifecycle did not reach the expected state", condition.getAsBoolean());
    }

    private static String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
