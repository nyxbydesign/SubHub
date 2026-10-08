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
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
                activity.onActivity(a -> assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0));
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
