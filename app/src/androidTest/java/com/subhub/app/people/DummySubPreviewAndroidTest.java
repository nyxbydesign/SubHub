package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.OwnedUiIntegrationFixture;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.remote.DummyPreviewEndpointFixture;
import com.subhub.app.remote.PairingActivity;
import com.subhub.app.remote.RemoteUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.PrimaryHeader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.*;

/** Real Activity navigation and input, with a private offline peer endpoint, never a dummy authority. */
public final class DummySubPreviewAndroidTest {
    @Rule public final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private DummyPreviewEndpointFixture endpoint;
    private static final String[] STORES = {"subhub_dummy_preview", "subhub_profile_names",
            SettingsRepository.PREFERENCES_NAME, SubFeaturePolicy.STORE, "subhub_relationship_presentation"};

    @Before public void prepare() throws Exception {
        for (String store : STORES) saved.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        assertFalse("Use an idle, owned UI guest", LocalSessionControls.isActive(context));
        assertTrue(context.getSharedPreferences("subhub_dummy_preview", 0).edit().clear().commit());
        assertTrue(new SettingsRepository(context).preferences().edit().putString(DeviceRolePreferences.KEY_MODE,
                DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
        endpoint = new DummyPreviewEndpointFixture(context);
        instrumentation.waitForIdleSync();
    }
    @After public void restore() throws Exception {
        integration.drainReaders();
        try { if (endpoint != null) endpoint.close(); }
        finally { for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue()); }
    }

    @Test public void directConnectionFiveSecondHoldAddsAndRemovesWithoutOpeningPreview() throws Exception {
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            Instrumentation.ActivityMonitor previews = instrumentation.addMonitor(DummySubPreviewActivity.class.getName(), null, false);
            try {
                for (boolean expected : new boolean[]{true, false}) {
                    PairingActivity pairing = openConnection(host.get());
                    Map<String, List<String>> rows = endpoint.snapshot(); Map<String, Map<String, ?>> before = realPreferences();
                    Rect back = backBounds(pairing); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
                    try {
                        SystemClock.sleep(4700);
                        assertEquals("A platform long click must not toggle the five-second gesture", !expected, DummySubPreview.isEnabled(context));
                        await(() -> pairing.isFinishing() || pairing.isDestroyed());
                        assertTrue("The hold must last five full seconds", SystemClock.uptimeMillis() - down >= 5000);
                    } finally { pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY()); }
                    await(() -> resumed(host.get()));
                    assertEquals(expected, DummySubPreview.isEnabled(context));
                    await(() -> (tagged(host.get(), "dummy_sub_card") != null) == expected);
                    assertEquals("Holding Back must not open preview", 0, previews.getHits());
                    assertRealState(before, rows);
                    scenario.recreate(); scenario.onActivity(host::set); await(() -> resumed(host.get()));
                    assertEquals(expected, DummySubPreview.isEnabled(context));
                    await(() -> (tagged(host.get(), "dummy_sub_card") != null) == expected);
                }
                assertEquals("Dummy Sub added", context.getString(com.subhub.app.R.string.dummy_sub_added));
                assertEquals("Dummy Sub removed", context.getString(com.subhub.app.R.string.dummy_sub_removed));
            } finally { instrumentation.removeMonitor(previews); }
        }
    }

    @Test public void cancelledAndUnscopedBackGesturesNeverToggle() throws Exception {
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            PairingActivity pairing = openConnection(host.get()); Rect back = backBounds(pairing);
            long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
            SystemClock.sleep(1000); pointer(MotionEvent.ACTION_CANCEL, down, back.centerX(), back.centerY());
            SystemClock.sleep(4200); assertFalse(DummySubPreview.isEnabled(context)); assertFalse(pairing.isFinishing());
            down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
            pointer(MotionEvent.ACTION_MOVE, down, back.right + 100, back.bottom + 100);
            SystemClock.sleep(5200); pointer(MotionEvent.ACTION_UP, down, back.right + 100, back.bottom + 100);
            assertFalse(DummySubPreview.isEnabled(context)); assertFalse(pairing.isFinishing());
            // A real competing window must cancel the pending hold even when the pointer remains down.
            down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
            AtomicReference<android.app.AlertDialog> dialog = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> dialog.set(new android.app.AlertDialog.Builder(pairing)
                    .setMessage("Owned focus-loss witness").setPositiveButton(android.R.string.ok, null).show()));
            await(() -> !pairing.hasWindowFocus());
            try { SystemClock.sleep(5200); assertFalse(DummySubPreview.isEnabled(context)); }
            finally {
                pointer(MotionEvent.ACTION_CANCEL, down, back.centerX(), back.centerY());
                instrumentation.runOnMainSync(() -> dialog.get().dismiss());
            }
            await(() -> resumed(pairing));
            // Short Back retains the actual production click listener and returns to its host.
            down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
            pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY());
            await(() -> resumed(host.get())); assertFalse(DummySubPreview.isEnabled(context));
        }
        // An asDom extra alone is not the internal, calling-Activity-verified invitation route.
        try (ActivityScenario<PairingActivity> scenario = ActivityScenario.launch(new Intent(context, PairingActivity.class)
                .putExtra("asDom", true).putExtra("com.subhub.app.remote.DIRECT_INVITATION", true))) {
            AtomicReference<PairingActivity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            Rect back = backBounds(page.get()); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
            SystemClock.sleep(5200); pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY());
            assertFalse(DummySubPreview.isEnabled(context));
        }
    }

    @Test public void rosterPreviewBrowsesEverySectionWithoutRealStateMutation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot();
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "dummy_sub_card") != null);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(DummySubPreviewActivity.class.getName(), null, false);
            try {
                onView(tag("dummy_sub_card")).perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
                capture(host.get(), "dummy-preview-roster");
                onView(tag("dummy_sub_card")).perform(click());
                DummySubPreviewActivity preview = (DummySubPreviewActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(preview); await(() -> resumed(preview));
                for (String section : new String[]{"modules", "censor", "limits", "wallet", "subliminal", "popup", "apps", "duration"}) {
                    onView(tag("dummy_preview_section:" + section)).perform(scrollTo(), click());
                    instrumentation.runOnMainSync(() -> {
                        assertNotNull(tagged(preview, "dummy_preview_page:" + section));
                        assertReadOnly(preview.findViewById(android.R.id.content));
                        if (section.equals("apps")) {
                            assertNotNull(tagged(preview, "dummy_preview_field:apps_scope"));
                            assertNotNull(tagged(preview, "dummy_preview_field:apps_selection"));
                        }
                        if (section.equals("duration")) {
                            assertNotNull(tagged(preview, "dummy_preview_field:duration_length"));
                            assertNotNull(tagged(preview, "dummy_preview_field:duration_status"));
                        }
                        for (com.subhub.app.configuration.ConfigurationSettingCatalog.Field field :
                                com.subhub.app.configuration.ConfigurationSettingCatalog.fields(section)) {
                            View row = tagged(preview, "dummy_preview_field:" + field.key);
                            assertNotNull("Catalog field missing: " + field.key, row); assertFalse(row.isClickable());
                        }
                    });
                    if (section.equals("modules")) capture(preview, "dummy-preview-settings");
                    assertRealState(before, rows);
                    Rect back = backBounds(preview); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
                    pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY());
                    await(() -> tagged(preview, "dummy_preview_home") != null);
                }
                instrumentation.runOnMainSync(preview::finish); await(() -> resumed(host.get()));
            } finally { instrumentation.removeMonitor(monitor); }
        }
        assertRealState(before, rows);
    }

    @Test public void previewRecreationAndRoleLossStayGuarded() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        try (ActivityScenario<DummySubPreviewActivity> scenario = ActivityScenario.launch(DummySubPreviewActivity.class)) {
            AtomicReference<DummySubPreviewActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "dummy_preview_home") != null);
            onView(tag("dummy_preview_section:modules")).perform(scrollTo(), click());
            await(() -> tagged(host.get(), "dummy_preview_page:modules") != null);
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "dummy_preview_page:modules") != null);
            scenario.onActivity(a -> {
                assertReadOnly(a.findViewById(android.R.id.content));
                for (com.subhub.app.configuration.ConfigurationSettingCatalog.Field field :
                        com.subhub.app.configuration.ConfigurationSettingCatalog.fields("modules"))
                    assertNotNull("Recreated catalog field missing: " + field.key, tagged(a, "dummy_preview_field:" + field.key));
            });
            ControllerPinManager.enterSubMode(); await(() -> host.get().isFinishing() || host.get().isDestroyed());
            assertFalse(DummySubPreview.isEnabled(context));
            assertTrue("Role loss hides, rather than deletes, the UI preference", context.getSharedPreferences("subhub_dummy_preview", 0).getBoolean("enabled", false));
        }
        try (ActivityScenario<DummySubPreviewActivity> ignored = ActivityScenario.launch(DummySubPreviewActivity.class)) {
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, ignored.getState());
        }
        ControllerPinManager.enterDomMode(); assertFalse(DummySubPreview.toggle(context));
        try (ActivityScenario<DummySubPreviewActivity> ignored = ActivityScenario.launch(DummySubPreviewActivity.class)) {
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, ignored.getState());
        }
        assertFalse(LocalSessionControls.isActive(context));
    }

    private void capture(Activity activity, String name) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
            instrumentation.runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
                root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
            });
            assertTrue("Fresh native frame", frame.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        android.graphics.Bitmap pixels = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(pixels);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "dummy-preview");
        assertTrue(run.matches("[a-zA-Z0-9-]+"));
        java.io.File directory = new java.io.File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) {
            assertTrue(pixels.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { pixels.recycle(); }
    }

    private PairingActivity openConnection(Activity host) {
        await(() -> resumed(host));
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PairingActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> new RemoteUi().openPairing(host, true));
            PairingActivity page = (PairingActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(page); await(() -> resumed(page) && tagged(page, "pairing_invitation_loading") == null); return page;
        } finally { instrumentation.removeMonitor(monitor); }
    }
    private Rect backBounds(Activity activity) {
        await(() -> resumed(activity)); AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View back = PrimaryHeader.backButton(activity.findViewById(android.R.id.content)); Rect bounds = new Rect();
            assertTrue(back.getGlobalVisibleRect(bounds)); assertEquals(back.getWidth(), bounds.width());
            assertEquals(back.getHeight(), bounds.height()); result.set(bounds);
        }); return result.get();
    }
    private long pointer(int action, long down, float x, float y) {
        long now = SystemClock.uptimeMillis(); if (down == 0) down = now;
        MotionEvent event = MotionEvent.obtain(down, now, action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).injectInputEvent(event, true)); }
        finally { event.recycle(); } return down;
    }
    private Map<String, Map<String, ?>> realPreferences() {
        Map<String, Map<String, ?>> result = new LinkedHashMap<>();
        for (String store : STORES) if (!store.equals("subhub_dummy_preview"))
            result.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        return result;
    }
    private void assertRealState(Map<String, Map<String, ?>> before, Map<String, List<String>> rows) throws Exception {
        assertTrue("Preview must not change profiles, settings, relationships or consent", before.equals(realPreferences()));
        assertTrue("Preview must not create peer, invitation, session, configuration or receipt rows", rows.equals(endpoint.snapshot()));
        assertFalse(LocalSessionControls.isActive(context));
    }
    private static void assertReadOnly(View view) {
        if (view instanceof EditText || view instanceof CompoundButton || view instanceof SeekBar || view instanceof Spinner)
            assertFalse("Preview field must not accept edits: " + view.getTag(), view.isEnabled());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertReadOnly(((ViewGroup) view).getChildAt(i));
    }
    private static View tagged(Activity activity, String tag) { return activity.findViewById(android.R.id.content).findViewWithTag(tag); }
    private static org.hamcrest.Matcher<View> tag(String value) { return withTagValue(is((Object) value)); }
    private static boolean resumed(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()
                && ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED;
    }
    private void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 10000;
        do { AtomicBoolean ready = new AtomicBoolean(); instrumentation.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return; SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < end);
        fail("Native preview UI did not reach the expected state");
    }
}
