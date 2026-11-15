package com.subhub.app.fullaccess;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.subhub.app.R;
import com.subhub.app.capture.MediaProjectionLeaseRegistry;
import com.subhub.app.censor.android.ProjectionTargetFixtureActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Requires an unlocked, owned API 35 guest with SubHub notifications already allowed. */
@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
public final class FullAccessScreenAndroidTest {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final long UI_TIMEOUT = 8_000;
    private static final BySelector START = By.pkg(SYSTEM_UI)
            .text(Pattern.compile("Start|Start now|Start recording|Start sharing|Share"));
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context target = instrumentation.getTargetContext();
    private UiDevice device;
    private FullAccessScreen.Session owned;
    private boolean fixtureStarted;

    @Before public void prepareOwnedGuest() {
        assertEquals("Grant notifications to the test APK's target on the owned guest",
                PackageManager.PERMISSION_GRANTED, target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        assertTrue("The owned guest must be awake and unlocked", FullAccessScreen.displayAvailable(target));
        assertNull("Projection belongs to another fixture", MediaProjectionLeaseRegistry.owner());
        device = UiDevice.getInstance(instrumentation);
        fixtureCommand("redraw");
        fixtureStarted = true;
        require("synthetic alternate screen", By.desc("Projection alternate source"));
    }

    @After public void closeOnlyOwnedFixture() {
        try {
            if (owned != null) {
                owned.close();
                await("Full Access released the projection lease and indicator", this::projectionRetired);
            }
        } finally {
            if (fixtureStarted) fixtureCommand("finish");
        }
    }

    @Test public void decliningAndroidPromptNeverOpensAProjectionOrReturnsFrames() {
        openSystemPrompt();
        assertEquals(FullAccessScreen.AWAITING, owned.status());
        assertNull(owned.takeLatest());
        assertNull(MediaProjectionLeaseRegistry.owner());

        require("Android's cancel action", By.pkg(SYSTEM_UI).text("Cancel")).click();
        await("Declined result from Android", () -> FullAccessScreen.DECLINED.equals(owned.status()));
        assertNull(owned.takeLatest());
        assertTrue(projectionRetired());
    }

    @Test public void approvingAndroidPromptSamplesChangingContentAndViewerCloseStopsCapture() {
        openSystemPrompt();
        selectEntireScreen();
        require("Android's screen-sharing approval action", START).click();

        await("Approved Full Access projection", () -> FullAccessScreen.STREAMING.equals(owned.status()));
        assertEquals(MediaProjectionLeaseRegistry.Owner.FULL_ACCESS, MediaProjectionLeaseRegistry.owner());
        Notification indicator = sharingIndicator();
        assertNotNull("Posted screen sharing indicator", indicator);
        assertNotNull("Local notification actions", indicator.actions);
        assertTrue("Posted local Stop sharing action", Arrays.stream(indicator.actions)
                .anyMatch(action -> target.getString(R.string.full_access_screen_stop).contentEquals(action.title)));

        fixtureCommand("redraw");
        require("synthetic alternate screen", By.desc("Projection alternate source"));
        awaitFrame(false);
        SystemClock.sleep(FullAccessScreenFrames.INTERVAL_MS);
        fixtureCommand("pulse");
        awaitFrame(true);

        owned.close();
        await("Viewer close retires the Android projection and foreground indicator", this::projectionRetired);
        assertEquals(FullAccessScreen.UNAVAILABLE, owned.status());
        assertNull(owned.takeLatest());
    }

    private void openSystemPrompt() {
        // Use the production background-notification path. Only the system dialog can grant projection.
        owned = FullAccessScreen.open(target, () -> true);
        assertTrue("System notification shade opened", device.openNotification());
        UiObject2 request = require("Full Access screen request notification",
                By.text(target.getString(R.string.full_access_screen_request)));
        UiAutomation automation = instrumentation.getUiAutomation();
        AtomicBoolean observed = new AtomicBoolean();
        var messages = Arrays.asList(target.getResources().getStringArray(R.array.full_access_screen_toasts));
        automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                    && event.getText().stream().map(CharSequence::toString).anyMatch(messages::contains))
                observed.set(true);
        });
        try {
            request.click();
            require("native Android MediaProjection dialog", By.pkg(SYSTEM_UI).text("Cancel"));
        } finally {
            automation.setOnAccessibilityEventListener(null);
        }
        Log.i("FullAccessScreenTest", "systemPrompt=true toastAccessibilityEvent=" + observed.get());
    }

    private void selectEntireScreen() {
        UiObject2 spinner = device.findObject(By.res(SYSTEM_UI, "screen_share_mode_spinner"));
        if (spinner == null) spinner = device.findObject(By.res(SYSTEM_UI, "screen_share_mode_options"));
        assertNotNull("Native screen sharing mode selector", spinner);
        spinner.click();
        require("native entire-screen option", By.pkg(SYSTEM_UI)
                .text(Pattern.compile("(?i)(?:share )?entire screen"))).click();
    }

    private boolean projectionRetired() {
        return MediaProjectionLeaseRegistry.owner() == null && sharingIndicator() == null;
    }

    private Notification sharingIndicator() {
        NotificationManager notifications = target.getSystemService(NotificationManager.class);
        if (notifications == null) return null;
        for (var row : notifications.getActiveNotifications()) {
            Notification posted = row.getNotification();
            if (FullAccessScreen.ACTIVE_CHANNEL.equals(posted.getChannelId())) return posted;
        }
        return null;
    }

    private void fixtureCommand(String command) {
        String pkg = instrumentation.getContext().getPackageName();
        target.startActivity(new Intent().setComponent(new ComponentName(pkg, ProjectionTargetFixtureActivity.class.getName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(command, true));
    }

    private void awaitFrame(boolean pulsed) {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT;
        do {
            byte[] jpeg = owned.takeLatest();
            if (jpeg != null) {
                assertTrue("Bounded JPEG frame", jpeg.length > 2 && jpeg.length <= FullAccessScreenFrames.MAX_BYTES);
                assertEquals(0xff, jpeg[0] & 0xff);
                assertEquals(0xd8, jpeg[1] & 0xff);
                Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                assertNotNull("JPEG decodes", frame);
                try {
                    assertTrue("Bounded native frame geometry", Math.max(frame.getWidth(), frame.getHeight()) <= 960);
                    if (hasMarker(frame, pulsed)) return;
                } finally {
                    frame.recycle();
                }
            }
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("No live JPEG of the " + (pulsed ? "white" : "magenta") + " synthetic marker; state=" + owned.status());
    }

    private static boolean hasMarker(Bitmap frame, boolean pulsed) {
        // The fixture draws its center marker over solid black. Allow JPEG and density scaling.
        int radius = Math.min(frame.getWidth(), frame.getHeight()) / 12;
        int centerX = frame.getWidth() / 2;
        int centerY = frame.getHeight() / 2;
        for (int y = centerY - radius; y < centerY + radius; y++) {
            for (int x = centerX - radius; x < centerX + radius; x++) {
                int pixel = frame.getPixel(x, y);
                int green = Color.green(pixel);
                if (Color.red(pixel) > 160 && Color.blue(pixel) > 160 && (pulsed ? green > 160 : green < 100))
                    return true;
            }
        }
        return false;
    }

    private UiObject2 require(String what, BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), UI_TIMEOUT);
        assertNotNull(what, object);
        return object;
    }

    private static void await(String what, BooleanSupplier ready) {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT;
        do {
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue(what, ready.getAsBoolean());
    }
}
