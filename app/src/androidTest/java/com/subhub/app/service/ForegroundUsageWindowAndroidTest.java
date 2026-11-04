package com.subhub.app.service;

import static org.junit.Assert.*;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.censor.android.ProductTargetFixtureActivity;
import com.subhub.app.censor.android.SnapshotViewFixtureActivity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

/** Real native windows through the production reader; screen/lock gates remain synthetic inputs. */
@SdkSuppress(minSdkVersion = 29)
public final class ForegroundUsageWindowAndroidTest {
    @Rule public final UiAutomationStateRule automationState = new UiAutomationStateRule();

    @Test public void nativeFocusAndVisibleRootsSeparateBriefWindowsFromContinuousUsage() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context target = instrumentation.getTargetContext();
        Context fixture = instrumentation.getContext();
        String targetPackage = target.getPackageName(), fixturePackage = fixture.getPackageName();
        assertNotEquals(targetPackage, fixturePackage);
        assertTrue("Requires an interactive owned UI guest", target.getSystemService(android.os.PowerManager.class).isInteractive());
        assertFalse("Unlock the owned UI guest before this fixture", target.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());

        // Connect before Activity creation; a late connection can miss initial focus/root state.
        UiAutomation automation = instrumentation.getUiAutomation();
        AccessibilityServiceInfo info = automation.getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        instrumentation.waitForIdleSync();
        JSONArray observations = new JSONArray();
        JSONObject receipt = new JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("window_source", "UiAutomation.getWindows through production ForegroundUsageWindowReader")
                .put("power_and_keyguard", "Actual initial state; off/lock are policy-only inputs")
                .put("third_party_material_islands", "Not installed or exercised by this fixture")
                .put("observations", observations);
        File evidence = new File(target.getCacheDir(), "foreground-usage-native-" + java.util.UUID.randomUUID() + ".json");
        assertTrue("Refuse to overwrite evidence", evidence.createNewFile());
        boolean remoteStarted = false;
        try (ActivityScenario<SnapshotViewFixtureActivity> parent = ActivityScenario.launch(SnapshotViewFixtureActivity.class)) {
            awaitPackage(automation, targetPackage);
            nativeWitness(automation, targetPackage, "parent-focused", observations);
            parent.onActivity(activity -> assertTrue(activity.hasWindowFocus()));

            ForegroundUsageSession session = new ForegroundUsageSession();
            fixture.startActivity(new Intent(fixture, ProductTargetFixtureActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("label", "Synthetic usage window"));
            remoteStarted = true;
            awaitPackage(automation, fixturePackage);
            nativeWitness(automation, fixturePackage, "brief-fixture-focused", observations);
            long briefStart = SystemClock.elapsedRealtime(), briefCharged = 0L;
            while (SystemClock.elapsedRealtime() - briefStart < 3_000L) {
                String actual = ForegroundUsageWindowReader.read(automation.getWindows(), "", true, false);
                assertEquals(fixturePackage, actual);
                briefCharged += session.observe(actual, SystemClock.elapsedRealtime());
                SystemClock.sleep(100L);
            }
            assertEquals("A real three-second focused window must not spend allowance", 0L, briefCharged);
            assertFalse(session.qualified(fixturePackage));
            finishRemote(fixture);
            remoteStarted = false;
            awaitPackage(automation, targetPackage);
            long resumedDeadline = SystemClock.uptimeMillis() + 5_000L;
            while (parent.getState() != androidx.lifecycle.Lifecycle.State.RESUMED
                    && SystemClock.uptimeMillis() < resumedDeadline) SystemClock.sleep(20L);
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, parent.getState());
            parent.onActivity(activity -> assertTrue(activity.hasWindowFocus()));
            nativeWitness(automation, targetPackage, "parent-restored", observations);

            // A stale package-bearing signal cannot override a live native-window observation.
            AccessibilityEvent stale = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            try {
                stale.setPackageName(fixturePackage);
                assertNotEquals(stale.getPackageName().toString(),
                        ForegroundUsageWindowReader.read(automation.getWindows(), "", true, false));
            } finally { stale.recycle(); }

            assertEquals(0L, session.observe(targetPackage, SystemClock.elapsedRealtime()));
            long continuousStart = SystemClock.elapsedRealtime(), continuousCharged = 0L;
            while (!session.qualified(targetPackage)
                    && SystemClock.elapsedRealtime() - continuousStart < 7_000L) {
                String actual = ForegroundUsageWindowReader.read(automation.getWindows(), "", true, false);
                assertEquals(targetPackage, actual);
                continuousCharged += session.observe(actual, SystemClock.elapsedRealtime());
                SystemClock.sleep(100L);
            }
            assertTrue("Continuously visible focused native windows must qualify", session.qualified(targetPackage));
            assertTrue(continuousCharged >= ForegroundUsageSession.QUALIFY_MILLIS);
            assertTrue("Qualification must charge once, within the bounded observation gap",
                    continuousCharged <= ForegroundUsageSession.QUALIFY_MILLIS
                            + ForegroundUsageSession.MAX_OBSERVATION_GAP_MILLIS);

            List<AccessibilityWindowInfo> windows = automation.getWindows();
            assertEquals("", ForegroundUsageWindowReader.read(windows, "", false, false));
            assertEquals("", ForegroundUsageWindowReader.read(windows, "", true, true));
            assertEquals(0L, session.observe(ForegroundUsageWindowReader.read(windows, "", false, false), SystemClock.elapsedRealtime()));
            assertFalse(session.qualified(targetPackage));
            assertEquals(0L, session.observe(ForegroundUsageWindowReader.read(windows, "", true, false), SystemClock.elapsedRealtime()));
            receipt.put("brief_charged_ms", briefCharged).put("continuous_charged_ms", continuousCharged)
                    .put("off_and_lock_policy_exclude_native_snapshot", true).put("passed", true);
        } finally {
            try { if (remoteStarted) finishRemote(fixture); }
            finally {
                info.flags = originalFlags;
                automation.setServiceInfo(info);
                Files.write(evidence.toPath(), receipt.toString(2).getBytes(StandardCharsets.UTF_8));
                Bundle results = new Bundle();
                results.putString("foreground_usage_evidence", evidence.getAbsolutePath());
                instrumentation.addResults(results);
            }
        }
    }

    private static void finishRemote(Context fixture) {
        fixture.startActivity(new Intent(fixture, ProductTargetFixtureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("finish", true));
    }

    private static void awaitPackage(UiAutomation automation, String name) {
        long deadline = SystemClock.uptimeMillis() + 6_000L;
        String observed;
        do {
            observed = ForegroundUsageWindowReader.read(automation.getWindows(), "", true, false);
            if (name.equals(observed)) return;
            SystemClock.sleep(20L);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Expected owned fixture focus was not observed");
    }

    private static void nativeWitness(UiAutomation automation, String expected, String stage,
            JSONArray evidence) throws Exception {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (!window.isFocused()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            try {
                if (root == null || root.getPackageName() == null
                        || !expected.contentEquals(root.getPackageName())) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                JSONObject row = new JSONObject().put("stage", stage).put("type", window.getType())
                        .put("window_id", window.getId()).put("focused", window.isFocused())
                        .put("active", window.isActive()).put("root_visible", root.isVisibleToUser())
                        .put("width", bounds.width()).put("height", bounds.height());
                evidence.put(row);
                assertEquals(AccessibilityWindowInfo.TYPE_APPLICATION, window.getType());
                assertTrue(root.isVisibleToUser());
                assertFalse(bounds.isEmpty());
                return;
            } finally { if (root != null) root.recycle(); }
        }
        fail("No focused visible owned application root witness");
    }
}
