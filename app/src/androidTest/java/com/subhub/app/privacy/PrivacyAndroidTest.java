package com.subhub.app.privacy;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PrivacyAndroidTest {
    @org.junit.Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void subCanChangeLocalPrivacyFromSettingsAndDirectPage() throws Exception {
        try (LocalPrivacyFixture fixture = new LocalPrivacyFixture()) {
            assertSubPrivacyTaps(com.subhub.app.settings.GlobalSettingsActivity.class);
            assertSubPrivacyTaps(PrivacyActivity.class);
        }
    }
    @Test public void subCannotEnableAppLockWithoutAvailableDeviceAuthentication() throws Exception {
        org.junit.Assume.assumeTrue(androidx.biometric.BiometricManager.from(context)
                .canAuthenticate(AppUnlockActivity.AUTHENTICATORS)
                != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS);
        try (LocalPrivacyFixture fixture = new LocalPrivacyFixture();
                ActivityScenario<PrivacyActivity> page = ActivityScenario.launch(PrivacyActivity.class)) {
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.privacy_lock_toggle))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            page.onActivity(a -> {
                assertFalse(new PrivacyManager(a).isAppLockEnabled());
                assertFalse(((android.widget.CompoundButton) a.findViewById(R.id.privacy_lock_toggle)).isChecked());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(a.isFinishing());
            });
        }
    }
    private <T extends Activity> void assertSubPrivacyTaps(Class<T> screen) {
        PrivacyManager privacy = new PrivacyManager(context);
        // The app is already unlocked, as it must be before its privacy settings are reachable.
        assertTrue(privacy.setAppLock(true)); PrivacyLifecycle.unlock();
        try (ActivityScenario<T> page = ActivityScenario.launch(screen)) {
            page.onActivity(a -> {
                assertFalse(a.isFinishing()); assertFalse(ControllerPinManager.isDomModeActive());
                assertEquals(1f, a.findViewById(R.id.privacy_discreet_toggle).getAlpha(), 0f);
                assertEquals(1f, a.findViewById(R.id.privacy_lock_toggle).getAlpha(), 0f);
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.privacy_lock_toggle))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            assertFalse(privacy.isAppLockEnabled()); assertFalse(ControllerPinManager.isDomModeActive());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.privacy_discreet_toggle))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            assertTrue(privacy.isDiscreet()); assertFalse(ControllerPinManager.isDomModeActive());
            page.recreate();
            page.onActivity(a -> assertTrue(((android.widget.CompoundButton) a.findViewById(R.id.privacy_discreet_toggle)).isChecked()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.privacy_discreet_toggle))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            assertFalse(privacy.isDiscreet()); assertFalse(ControllerPinManager.isDomModeActive());
        }
    }
    private final class LocalPrivacyFixture implements AutoCloseable {
        private final java.util.Map<String,?> privacyBefore = new java.util.HashMap<>(context.getSharedPreferences("subhub_privacy",0).getAll());
        private final java.util.Map<String,?> settingsBefore = new java.util.HashMap<>(new com.subhub.app.settings.SettingsRepository(context).preferences().getAll());
        private final boolean originalDom = ControllerPinManager.isDomModeActive();
        private final ComponentName normal = new ComponentName(context,"com.subhub.app.DefaultLauncher"), neutral = new ComponentName(context,"com.subhub.app.DiscreetLauncher");
        private final int normalBefore = context.getPackageManager().getComponentEnabledSetting(normal), neutralBefore = context.getPackageManager().getComponentEnabledSetting(neutral);
        private final Object lifecycle;
        private final java.lang.reflect.Field unlocked;
        private final boolean unlockedBefore;
        LocalPrivacyFixture() throws Exception {
            java.lang.reflect.Field instance = PrivacyLifecycle.class.getDeclaredField("instance"); instance.setAccessible(true); lifecycle = instance.get(null); assertNotNull(lifecycle);
            unlocked = PrivacyLifecycle.class.getDeclaredField("unlocked"); unlocked.setAccessible(true); unlockedBefore = unlocked.getBoolean(lifecycle);
            assertTrue(new com.subhub.app.settings.SettingsRepository(context).preferences().edit()
                    .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,com.subhub.app.security.DeviceRolePreferences.Mode.SUB_ONLY.name()).commit());
            ControllerPinManager.enterSubMode();
            PrivacyManager privacy = new PrivacyManager(context);
            assertTrue(privacy.setAppLock(false)); assertTrue(privacy.setDiscreet(false)); PrivacyLifecycle.unlock();
        }
        @Override public void close() throws Exception {
            PrivacyManager privacy = new PrivacyManager(context);
            assertTrue(privacy.setAppLock(false));
            assertTrue(privacy.setDiscreet(Boolean.TRUE.equals(privacyBefore.get("discreet"))));
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(context.getSharedPreferences("subhub_privacy",0),privacyBefore);
            com.subhub.app.settings.SharedPreferenceTestRestore.restore(new com.subhub.app.settings.SettingsRepository(context).preferences(),settingsBefore);
            context.getPackageManager().setComponentEnabledSetting(normal,normalBefore,PackageManager.DONT_KILL_APP);
            context.getPackageManager().setComponentEnabledSetting(neutral,neutralBefore,PackageManager.DONT_KILL_APP);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                try { unlocked.setBoolean(lifecycle,unlockedBefore); } catch (IllegalAccessException unavailable) { throw new AssertionError(unavailable); }
                PrivacyLifecycle.refresh();
            });
            if(originalDom)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();
        }
    }
    @Test public void discreetAliasAndNotificationPersistWithoutChangingActions() {
        PrivacyManager privacy = new PrivacyManager(context);
        PendingIntent action = PendingIntent.getActivity(context, 9961, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("privacy_test", "Fixture", NotificationManager.IMPORTANCE_LOW));
        Notification source = new Notification.Builder(context, "privacy_test").setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Sensitive fixture").setContentText("Private details").setContentIntent(action)
                .addAction(new Notification.Action.Builder(null, "Cancel", action).build()).setOngoing(true).build();
        try {
            assertTrue(privacy.setDiscreet(true));
            assertTrue(new PrivacyManager(context).isDiscreet());
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.getPackageManager().getComponentEnabledSetting(new ComponentName(context, "com.subhub.app.DiscreetLauncher")));
            Notification masked = PrivacyNotifications.present(context, 9961, source);
            assertEquals("Screen Filter", masked.extras.getString(Notification.EXTRA_TITLE));
            assertFalse(masked.extras.toString().contains("Sensitive fixture"));
            assertEquals(action, masked.contentIntent); assertEquals(action, masked.actions[0].actionIntent);
            assertTrue((masked.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN))) {
                activity.onActivity(a -> assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE));
            }
        } finally { assertTrue(privacy.setDiscreet(false)); }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.getPackageManager().getComponentEnabledSetting(new ComponentName(context, "com.subhub.app.DefaultLauncher")));
        assertSame(source, PrivacyNotifications.present(context, 9961, source));
    }
    @Test public void appEntryLockDoesNotGrantDomOrFallThroughOnMissingEnrollment() {
        PrivacyManager privacy = new PrivacyManager(context);
        ControllerPinManager.enterSubMode();
        try {
            assertTrue(privacy.setAppLock(true));
            try (ActivityScenario<AppUnlockActivity> activity = ActivityScenario.launch(AppUnlockActivity.class)) {
                activity.onActivity(a -> {
                    assertNotNull(a.findViewById(R.id.privacy_unlock_button));
                    assertFalse(ControllerPinManager.isDomModeActive());
                    assertFalse(a.isFinishing());
                });
            }
        } finally { privacy.setAppLock(false); }
    }
}
