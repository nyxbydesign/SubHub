package com.subhub.app.appmode;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.platform.app.InstrumentationRegistry;

import com.subhub.app.R;
import com.subhub.app.TextFrameWitness;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.UiRefreshWitness;
import com.subhub.app.people.PersonId;
import com.subhub.app.remote.ExtraTimeRequests;
import com.subhub.app.remote.RemoteExtraTimeUiFixture;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Real request/decision controls against the production facade on an explicitly owned clean guest. */
public final class ExtraTimeUiAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private Context context;

    @Before public void admit() {
        assertEquals("Owned fresh UI fixture only", "true", InstrumentationRegistry.getArguments()
                .getString("extra_time_ui_fixture"));
        assertTrue(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse(com.subhub.app.service.ScreenCaptureService.isRunning());
        assertFalse(com.subhub.app.service.ScreenshotAccessibilityService.isRunning());
        // Establish the UI automation connection before any target views are created.
        InstrumentationRegistry.getInstrumentation().getUiAutomation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void requestApproveAndDeclineRetainAllowanceAndInputDrafts() throws Exception {
        assertTrue("Normal-font contract", context.getResources().getConfiguration().fontScale < 1.4f);
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            String firstId;
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.firstPackage)) {
                await(sub, a -> tagged(a, "extra_time_submit:" + fixture.firstPackage) != null);
                sub.onActivity(ExtraTimeUiAndroidTest::assertFocusCatcher);
                capture(sub, text(R.string.extra_time_reached), "extra-time-sub-request");
                tap("extra_time_submit:" + fixture.firstPackage);
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                sub.onActivity(a -> assertNull(tagged(a, "extra_time_submit:" + fixture.firstPackage)));
                sub.recreate();
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                firstId = request(fixture.subRequests(), fixture.firstPackage).id;
                capture(sub, text(R.string.extra_time_pending), "extra-time-sub-pending");
            }
            fixture.transfer();
            fixture.switchDom();
            try (ActivityScenario<ExtraTimeActivity> dom = open(fixture.subPerson, null)) {
                String inputTag = "extra_time_minutes:" + firstId;
                await(dom, a -> tagged(a, inputTag) != null);
                dom.onActivity(a -> {
                    assertFalse(tagged(a, inputTag).hasFocus());
                    assertTrue(tagged(a, inputTag).isFocusableInTouchMode());
                    assertFocusCatcher(a);
                });
                tap(inputTag);
                dom.onActivity(a -> assertTrue("Native tap still focuses the minutes control", tagged(a, inputTag).hasFocus()));
                onView(withTagValue(equalTo(inputTag))).perform(clearText(), typeText("25"), closeSoftKeyboard());
                dom.recreate();
                await(dom, a -> tagged(a, inputTag) instanceof EditText
                        && "25".contentEquals(((EditText) tagged(a, inputTag)).getText()));
                dom.onActivity(a -> {
                    assertFalse("Recreation must not reopen the keyboard", tagged(a, inputTag).hasFocus());
                    assertFocusCatcher(a);
                });
                capture(dom, text(R.string.extra_time_minutes), "extra-time-dom-review");
                tap("extra_time_approve:" + firstId);
                await(dom, a -> hasText(a, a.getString(R.string.extra_time_approved, 25)));
                assertEquals(25, request(fixture.domRequests(), fixture.firstPackage).approvedMinutes);
            }
            fixture.transfer();
            fixture.switchSub();
            ExtraTimeRequests.App approved = app(fixture.subRequests(), fixture.firstPackage);
            assertEquals("One-time bonus", 25, approved.bonusMinutes);
            assertEquals("Daily allowance is unchanged", 5, approved.dailyMinutes);
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.firstPackage)) {
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_approved, 25)));
                assertTrue(approved.remainingMillis > 0);
                capture(sub, textWithValue(context.getString(R.string.extra_time_approved, 25)), "extra-time-sub-approved");
            }
            String secondId;
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.secondPackage)) {
                await(sub, a -> tagged(a, "extra_time_submit:" + fixture.secondPackage) != null);
                tap("extra_time_submit:" + fixture.secondPackage);
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                secondId = request(fixture.subRequests(), fixture.secondPackage).id;
            }
            fixture.transfer();
            fixture.switchDom();
            try (ActivityScenario<ExtraTimeActivity> dom = open(fixture.subPerson, null)) {
                await(dom, a -> tagged(a, "extra_time_decline:" + secondId) != null);
                tap("extra_time_decline:" + secondId);
                await(dom, a -> hasText(a, a.getString(R.string.extra_time_declined)));
            }
            fixture.transfer();
            fixture.switchSub();
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.secondPackage)) {
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_declined)));
                assertEquals(0, app(fixture.subRequests(), fixture.secondPackage).bonusMinutes);
                assertEquals(10, app(fixture.subRequests(), fixture.secondPackage).dailyMinutes);
                capture(sub, text(R.string.extra_time_declined), "extra-time-sub-declined");
            }
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void unavailableRequestExplainsItsStateAndRecoversBeforeOneTapSubmission() throws Exception {
        assertTrue("Normal-font contract", context.getResources().getConfiguration().fontScale < 1.4f);
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            android.content.SharedPreferences settings = context.getSharedPreferences(
                    com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0);
            // Change only this explicitly owned inactive guest's synthetic session setting.
            assertTrue(settings.edit().putBoolean(AppModeManager.KEY_ARMED, false).commit());
            String tag = "extra_time_submit:" + fixture.firstPackage;
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.firstPackage)) {
                await(sub, a -> tagged(a, tag) != null);
                sub.onActivity(a -> {
                    TextView button = (TextView) tagged(a, tag);
                    assertFalse(button.isEnabled());
                    assertEquals(a.getString(R.string.extra_time_request_unavailable), button.getText().toString());
                    assertEquals(0.5f, button.getAlpha(), 0.01f);
                    TextView reason = (TextView) tagged(a, "extra_time_unavailable_reason:" + fixture.firstPackage);
                    assertNotNull("Explain the disabled control next to the control", reason);
                    assertEquals("App limits must be active to request time", reason.getText().toString());
                });
                assertTrue("Displaying an unavailable action never submits", fixture.subRequests()
                        .snapshot(System.currentTimeMillis()).requests.isEmpty());
                capture(sub, text(R.string.extra_time_request_unavailable), "extra-time-sub-unavailable");
                sub.recreate();
                await(sub, a -> tagged(a, tag) != null && !tagged(a, tag).isEnabled());
                assertTrue(settings.edit().putBoolean(AppModeManager.KEY_ARMED, true).commit());
                await(sub, a -> tagged(a, tag) != null && tagged(a, tag).isEnabled());
                sub.onActivity(a -> {
                    TextView button = (TextView) tagged(a, tag);
                    assertEquals(a.getString(R.string.extra_time_request), button.getText().toString());
                    assertEquals(1f, button.getAlpha(), 0.01f);
                    assertNull(tagged(a, "extra_time_unavailable_reason:" + fixture.firstPackage));
                });
                tap(tag);
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                ExtraTimeRequests.Snapshot result = fixture.subRequests().snapshot(System.currentTimeMillis());
                assertEquals("One native tap submits exactly one request", 1, result.requests.size());
                assertEquals(fixture.firstPackage, result.requests.get(0).packageName);
                assertEquals(5, app(fixture.subRequests(), fixture.firstPackage).dailyMinutes);
            }
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void unsupportedPeerExplainsCapabilityAndRecoversAfterNegotiation() throws Exception {
        assertTrue("Normal-font contract", context.getResources().getConfiguration().fontScale < 1.4f);
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            assertTrue(context.getSharedPreferences(com.subhub.app.penance.PenanceManager.PREFS_NAME, 0)
                    .edit().putBoolean("rule_extra_time_request_enabled", false).commit());
            fixture.setSubAppTimeSupport(false);
            String tag = "extra_time_submit:" + fixture.firstPackage;
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.firstPackage)) {
                await(sub, a -> tagged(a, tag) != null);
                sub.onActivity(a -> {
                    TextView button = (TextView) tagged(a, tag);
                    assertFalse(button.isEnabled());
                    assertEquals(a.getString(R.string.extra_time_request_unavailable), button.getText().toString());
                    TextView reason = (TextView) tagged(a, "extra_time_unavailable_reason:" + fixture.firstPackage);
                    assertNotNull(reason);
                    assertEquals("App-time request support has not been confirmed. Reconnect with your Dom and check that both apps are up to date.",
                            reason.getText().toString());
                    assertTrue(hasText(a, a.getString(R.string.extra_time_no_tribute)));
                });
                capture(sub, text(R.string.extra_time_request_unavailable), "extra-time-sub-support-unconfirmed");
                assertTrue(fixture.subRequests().snapshot(System.currentTimeMillis()).requests.isEmpty());
                // A freshly confirmed supported handshake, not a tap, makes submission available.
                fixture.setSubAppTimeSupport(true);
                await(sub, a -> tagged(a, tag) != null && tagged(a, tag).isEnabled());
                tap(tag);
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                ExtraTimeRequests.Snapshot result = fixture.subRequests().snapshot(System.currentTimeMillis());
                assertEquals(1, result.requests.size());
                assertEquals(0, result.requests.get(0).tributeCents);
                assertTrue(new com.subhub.app.penance.PenanceManager(context)
                        .snapshot(System.currentTimeMillis()).getEvents().isEmpty());
            }
        }
    }

    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    public void largeFontNarrowReviewKeepsControlsAndReceiptsReadable() throws Exception {
        assertTrue("Set font scale only on the owned fresh guest before this distinct contract",
                context.getResources().getConfiguration().fontScale >= 1.4f);
        try (RemoteExtraTimeUiFixture fixture = RemoteExtraTimeUiFixture.setupSub(context)) {
            assertTrue(context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                    .edit().putBoolean(AppTimerManager.KEY_PER_APP_ENABLED, false)
                    .putBoolean(AppTimerManager.KEY_TOTAL_ENABLED, true)
                    .putInt(AppTimerManager.KEY_TOTAL_MINUTES, 15).commit());
            String id;
            try (ActivityScenario<ExtraTimeActivity> sub = open(PersonId.LOCAL, fixture.firstPackage)) {
                await(sub, a -> tagged(a, "extra_time_submit:" + fixture.firstPackage) != null);
                narrow(sub);
                sub.onActivity(ExtraTimeUiAndroidTest::assertFocusCatcher);
                capture(sub, text(R.string.extra_time_total_reached), "extra-time-sub-shared-only-large-font");
                sub.onActivity(a -> {
                    assertFalse("An inactive per-app allowance is not a daily denominator",
                            hasText(a, a.getString(R.string.extra_time_daily, 5)));
                    assertFalse("A shared-only app does not have individual-budget progress",
                            hasProgress(a.findViewById(android.R.id.content)));
                });
                tap("extra_time_submit:" + fixture.firstPackage);
                await(sub, a -> hasText(a, a.getString(R.string.extra_time_pending)));
                id = request(fixture.subRequests(), fixture.firstPackage).id;
            }
            fixture.transfer();
            fixture.switchDom();
            try (ActivityScenario<ExtraTimeActivity> dom = open(fixture.subPerson, null)) {
                String inputTag = "extra_time_minutes:" + id;
                await(dom, a -> tagged(a, inputTag) != null);
                dom.onActivity(a -> {
                    assertTrue(tagged(a, inputTag).isFocusableInTouchMode());
                    assertFocusCatcher(a);
                });
                narrow(dom);
                onView(withTagValue(equalTo(inputTag))).perform(reveal());
                capture(dom, text(R.string.extra_time_minutes), "extra-time-dom-large-font");
                dom.onActivity(a -> readable(a.findViewById(android.R.id.content)));
                tap(inputTag);
                dom.onActivity(a -> assertTrue("Native tap still focuses the minutes control", tagged(a, inputTag).hasFocus()));
                onView(withTagValue(equalTo(inputTag))).perform(clearText(), typeText("30"), closeSoftKeyboard());
                dom.recreate();
                await(dom, a -> tagged(a, inputTag) instanceof EditText
                        && "30".contentEquals(((EditText) tagged(a, inputTag)).getText()));
                narrow(dom);
                dom.onActivity(a -> {
                    assertFalse(tagged(a, inputTag).hasFocus());
                    assertFocusCatcher(a);
                    assertFalse("Historical request usage does not infer an active per-app budget",
                            hasText(a, a.getString(R.string.extra_time_daily, 5)));
                    assertFalse(hasProgress(a.findViewById(android.R.id.content)));
                    readable(a.findViewById(android.R.id.content));
                });
            }
        }
    }

    private ActivityScenario<ExtraTimeActivity> open(PersonId person, String packageName) {
        return ActivityScenario.launch(ExtraTimeActivity.intent(context, person, packageName));
    }

    private static void narrow(ActivityScenario<ExtraTimeActivity> scenario) throws Exception {
        scenario.onActivity(a -> {
            assertTrue(a.getResources().getConfiguration().fontScale >= 1.4f);
            a.getWindow().setLayout(Math.round(320 * a.getResources().getDisplayMetrics().density),
                    WindowManager.LayoutParams.MATCH_PARENT);
        });
        await(scenario, a -> a.getWindow().getDecorView().getWidth() > 0
                && a.getWindow().getDecorView().getWidth()
                <= Math.round(320 * a.getResources().getDisplayMetrics().density) + 1);
    }

    private static View tagged(ExtraTimeActivity activity, String tag) {
        return activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }

    private static void assertFocusCatcher(ExtraTimeActivity activity) {
        View intro = tagged(activity, "extra_time_intro");
        assertNotNull("The extra-time page has rendered its content", intro);
        assertTrue(intro.getParent() instanceof android.widget.LinearLayout);
        View catcher = (View) intro.getParent();
        assertFalse("The configured focus catcher is not an action", catcher.isClickable());
        assertTrue(catcher.isFocusableInTouchMode());
        assertFalse("The focus catcher does not draw over the app's cards", catcher.getDefaultFocusHighlightEnabled());
        assertFalse("Launch/recreation must not focus a time editor", activity.getCurrentFocus() instanceof EditText);
    }

    private static void tap(String tag) {
        onView(withTagValue(equalTo(tag))).perform(reveal(), click());
    }

    private static ViewAction reveal() {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() { return isAssignableFrom(View.class); }
            @Override public String getDescription() { return "reveal the complete extra-time control inside the viewport"; }
            @Override public void perform(UiController ui, View view) {
                ScrollView scroll = UiRefreshWitness.scroll(view);
                Rect relative = new Rect(); view.getDrawingRect(relative);
                scroll.offsetDescendantRectToMyCoords(view, relative);
                scroll.scrollTo(0, Math.max(0, relative.top - scroll.getHeight() / 3));
                ui.loopMainThreadUntilIdle();
                int[] origin = new int[2]; view.getLocationOnScreen(origin);
                Rect full = new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
                Rect visible = TextFrameWitness.screenClip(view);
                Rect window = new Rect(); view.getWindowVisibleDisplayFrame(window);
                assertTrue("Full control visible: " + full + " clip=" + visible, visible.contains(full));
                assertTrue("Full control clear of system/keyboard insets", window.contains(full));
            }
        };
    }

    private static void await(ActivityScenario<ExtraTimeActivity> page, Predicate<ExtraTimeActivity> condition)
            throws Exception {
        long until = SystemClock.uptimeMillis() + 8_000;
        while (SystemClock.uptimeMillis() < until) {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            page.onActivity(activity -> ready.set(condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(40);
        }
        page.onActivity(activity -> assertTrue("Expected extra-time state did not render", condition.test(activity)));
    }

    private static boolean hasText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (hasText(((ViewGroup) root).getChildAt(i), value)) return true;
        return false;
    }

    private static boolean hasText(ExtraTimeActivity activity, String value) {
        return hasText(activity.findViewById(android.R.id.content), value);
    }

    private static Predicate<TextView> text(int resource) {
        return value -> value.getContext().getString(resource).contentEquals(value.getText());
    }

    private static Predicate<TextView> textWithValue(String expected) {
        return value -> expected.contentEquals(value.getText());
    }

    private static TextView findText(View root, Predicate<TextView> predicate) {
        if (root instanceof TextView && predicate.test((TextView) root)) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) root).getChildAt(i), predicate);
            if (found != null) return found;
        }
        return null;
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    private static void capture(ActivityScenario<ExtraTimeActivity> scenario, Predicate<TextView> predicate,
            String name) throws Exception {
        AtomicReference<TextView> target = new AtomicReference<>();
        scenario.onActivity(a -> target.set(findText(a.findViewById(android.R.id.content), predicate)));
        assertNotNull("Capture target " + name, target.get());
        onView(withText(target.get().getText().toString())).perform(reveal());
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "extra-time-ui");
        assertTrue(run.matches("[A-Za-z0-9_-]+") && name.matches("[A-Za-z0-9_.-]+"));
        java.io.File folder = new java.io.File(target.get().getContext().getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        java.io.File diagnostic = new java.io.File(folder, name + ".input-mode.json");
        assertFalse("Preserve earlier input-mode evidence", diagnostic.exists());
        AtomicReference<org.json.JSONObject> before = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> before.set(focusState(target.get())));
        boolean initialTouchMode = before.get().getBoolean("targetInTouchMode");
        org.json.JSONObject evidence = new org.json.JSONObject().put("before", before.get())
                .put("requestedTouchMode", true).put("restoreInitialTouchMode", initialTouchMode);
        try {
            // Key injection plus recreation can leave the focusable content's framework highlight
            // above its children. Capture ordinary touch presentation without weakening pixel proof.
            instrumentation.setInTouchMode(true);
            org.json.JSONObject after = awaitTouchMode(target.get(), true, evidence, "captureModeObservations");
            evidence.put("after", after);
            assertTrue("Capture uses the actual target's touch mode", after.getBoolean("targetInTouchMode"));
            assertTrue("Capture target remains attached and focused",
                    after.getBoolean("targetAttached") && after.getBoolean("windowFocused"));
            TextFrameWitness.capture(target.get(), UiRefreshWitness.scroll(target.get()), run, name);
            scenario.onActivity(a -> readable(a.findViewById(android.R.id.content)));
        } finally {
            try {
                instrumentation.setInTouchMode(initialTouchMode);
                org.json.JSONObject restored = awaitTouchMode(target.get(), initialTouchMode,
                        evidence, "restoreModeObservations");
                evidence.put("restored", restored);
                assertEquals("Restore the fixture's previous input modality", initialTouchMode,
                        restored.getBoolean("targetInTouchMode"));
                assertTrue("Restored target remains attached and focused",
                        restored.getBoolean("targetAttached") && restored.getBoolean("windowFocused"));
            } finally {
                java.nio.file.Files.write(diagnostic.toPath(), evidence.toString(2).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
    }

    private static org.json.JSONObject awaitTouchMode(View target, boolean expected,
            org.json.JSONObject evidence, String observationsKey) throws Exception {
        // setInTouchMode crosses into WindowManager. An idle application queue does not
        // establish that the resulting input-mode notification has reached this ViewRoot.
        // Observe the actual target without reinjecting input or changing its focus.
        org.json.JSONArray observations = new org.json.JSONArray();
        evidence.put(observationsKey, observations);
        long started = SystemClock.uptimeMillis();
        org.json.JSONObject observed;
        do {
            AtomicReference<org.json.JSONObject> sample = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> sample.set(focusState(target)));
            observed = sample.get();
            observed.put("elapsedMillis", SystemClock.uptimeMillis() - started);
            observations.put(observed);
            if (observed.getBoolean("targetAttached") && observed.getBoolean("windowFocused")
                    && observed.getBoolean("targetInTouchMode") == expected) return observed;
            if (SystemClock.uptimeMillis() - started >= 8_000L) return observed;
            SystemClock.sleep(40);
        } while (true);
    }

    private static org.json.JSONObject focusState(View target) {
        try {
            View focus = target.getRootView().findFocus();
            org.json.JSONObject value = new org.json.JSONObject().put("targetInTouchMode", target.isInTouchMode())
                    .put("targetAttached", target.isAttachedToWindow())
                    .put("windowFocused", target.getRootView().hasWindowFocus())
                    .put("targetTag", String.valueOf(target.getTag()));
            if (focus == null) return value.put("focusedClass", org.json.JSONObject.NULL);
            int[] origin = new int[2]; focus.getLocationOnScreen(origin);
            return value.put("focusedClass", focus.getClass().getName()).put("focusedTag", String.valueOf(focus.getTag()))
                    .put("focusedInTouchMode", focus.isInTouchMode()).put("focusedHasFocus", focus.hasFocus())
                    .put("defaultFocusHighlightEnabled", focus.getDefaultFocusHighlightEnabled())
                    .put("focusedBounds", new Rect(origin[0], origin[1], origin[0] + focus.getWidth(),
                            origin[1] + focus.getHeight()).flattenToString());
        } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
    }

    private static void readable(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (text.length() > 0 && layout != null) for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals("No clipped extra-time copy: " + text.getText(), 0, layout.getEllipsisCount(line));
                assertTrue("Text fits its allotted width: " + text.getText(), layout.getLineMax(line)
                        <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1.5f);
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            readable(((ViewGroup) view).getChildAt(i));
    }

    private static boolean hasProgress(View view) {
        if (view instanceof android.widget.ProgressBar) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasProgress(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }

    private static ExtraTimeRequests.Request request(ExtraTimeRequests repository, String packageName) throws Exception {
        for (ExtraTimeRequests.Request request : repository.snapshot(System.currentTimeMillis()).requests)
            if (request.packageName.equals(packageName)) return request;
        throw new AssertionError("Missing real app-time request");
    }

    private static ExtraTimeRequests.App app(ExtraTimeRequests repository, String packageName) throws Exception {
        for (ExtraTimeRequests.App app : repository.snapshot(System.currentTimeMillis()).apps)
            if (app.packageName.equals(packageName)) return app;
        throw new AssertionError("Missing app allowance");
    }
}
