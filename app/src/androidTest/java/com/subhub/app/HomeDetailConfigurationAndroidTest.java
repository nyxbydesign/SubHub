package com.subhub.app;

import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.people.ContractActivity;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public final class HomeDetailConfigurationAndroidTest {
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private SharedPreferences prefs, auth;
    private Map<String, ?> before, authBefore;
    @Before public void prepare() throws Exception {
        prefs = new SettingsRepository(context).preferences();
        auth = context.getSharedPreferences("subhub_controller_auth", Context.MODE_PRIVATE);
        before = new java.util.LinkedHashMap<>(prefs.getAll()); authBefore = new java.util.LinkedHashMap<>(auth.getAll());
        prefs.edit().putBoolean(AppModeManager.KEY_ARMED, false).remove("commitment_ends_at")
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,
                        com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()).commit();
        if (!ControllerPinManager.isConfigured(context)) assertTrue(ControllerPinManager.setPin(context, "2468"));
        String chrome = "com.android.chrome", settings = "com.android.settings";
        context.getPackageManager().getApplicationInfo(chrome, 0);
        context.getPackageManager().getApplicationInfo(settings, 0);
        AppModeManager apps = new AppModeManager(context);
        apps.setAllAppsEnabled(false);
        apps.saveIncludedPackages(Set.of(chrome, settings));
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(Set.of(chrome, settings), Map.of(chrome, 35, settings, 75));
        assertAllowances();
        ControllerPinManager.enterSubMode();
    }
    @After public void restore() {
        ControllerPinManager.enterDomMode();
        SharedPreferenceTestRestore.restore(prefs, before); SharedPreferenceTestRestore.restore(auth, authBefore);
        ControllerPinManager.enterSubMode();
    }
    @Test public void limitsListsDistinctInstalledAppAllowancesAndEmptySelection() throws Exception {
        try (ActivityScenario<ContractActivity> scenario = launch()) {
            awaitContract(scenario);
            openContractSection("limits");
            inspect("home-limits-allowances.png", text -> {
                assertTrue(text.contains("Chrome · 35 min"));
                assertTrue(text.contains("Settings · 75 min"));
            });
            assertAllowances();
            new AppModeManager(context).saveIncludedPackages(Set.of());
            scenario.recreate();
            awaitContract(scenario);
            openContractSection("limits");
            inspect(null, text -> {
                assertTrue(text.contains(context.getString(R.string.arrangement_no_apps)));
                assertFalse(text.contains("Chrome · 35 min"));
                assertFalse(text.contains("Settings · 75 min"));
            });
            assertTrue(new AppModeManager(context).getIncludedPackages().isEmpty());
        }
    }
    @Test public void currentConfigurationAndSecurityShowTheirOwnDetailsWithoutSecrets() throws Exception {
        ControllerPinManager.enterDomMode();
        new com.subhub.app.settings.FeatureModuleManager(context).setSubliminalEnabled(true);
        prefs.edit().putBoolean(com.subhub.app.popup.PopupStormSettings.K_ENABLED, true).commit();
        auth.edit().putString("encrypted_secret", "SYNTHETIC_SECRET_DO_NOT_SHOW").commit();
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = launch()) {
            awaitContract(scenario);
            inspect("home-current-config.png", text -> {
                assertTrue(text.contains(context.getString(R.string.role_local_configuration)));
                assertFalse(text.contains(context.getString(R.string.keyholder_status_both)));
                assertFalse(text.contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
            });
            assertEffectDetails();
            assertAllowances();
            scenario.recreate();
            awaitContract(scenario);
            inspect(null, text -> assertTrue(text.contains(context.getString(R.string.role_local_configuration))));
            assertEffectDetails();
        }
        try (ActivityScenario<com.subhub.app.security.AuthenticatorActivity> security =
                ActivityScenario.launch(com.subhub.app.security.AuthenticatorActivity.class)) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            security.onActivity(this::assertSecurityDetails);
            security.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            security.onActivity(this::assertSecurityDetails);
        }
    }

    private void assertEffectDetails() {
        openContractSection("atmosphere");
        onView(withId(R.id.role_page_body)).check((body, missing) -> {
            if (missing != null) throw missing;
            View detail = body.findViewWithTag("contract_details:atmosphere");
            assertNotNull(detail);
            assertTrue(detail.isShown());
            String effects = text(detail);
            checkTextFits(detail);
            assertNotNull("Contract contains its effect settings", effects);
            assertTrue(effects.contains(context.getString(R.string.arrangement_detail_line,
                    context.getString(R.string.atmosphere_whispers_title),
                    context.getString(R.string.arrangement_atmosphere_enabled, ""))));
            assertTrue(effects.contains(context.getString(R.string.arrangement_detail_line,
                    context.getString(R.string.popup_title), context.getString(R.string.atmosphere_state_on))));
            assertFalse(effects.contains(context.getString(R.string.keyholder_home_title)));
            assertFalse(effects.contains(context.getString(R.string.keyholder_your_lock)));
        });
    }

    private static void openContractSection(String key) {
        AtomicBoolean expanded = new AtomicBoolean();
        onView(withTagValue(is("contract_details:" + key))).check((detail, missing) -> {
            if (missing != null) throw missing;
            expanded.set(detail.getVisibility() == View.VISIBLE);
        });
        if (!expanded.get()) onView(withTagValue(is("contract_section:" + key)))
                .perform(revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
        awaitCommittedFrame();
        onView(withTagValue(is("contract_details:" + key))).check((detail, missing) -> {
            if (missing != null) throw missing;
            assertTrue(detail.isShown());
            checkTextFits(detail);
        });
    }

    private void assertSecurityDetails(com.subhub.app.security.AuthenticatorActivity activity) {
        View content = activity.findViewById(android.R.id.content);
        String copy = text(content);
        assertTrue(copy.contains(context.getString(R.string.keyholder_home_title)));
        assertTrue(copy.contains(context.getString(R.string.keyholder_status_pin)));
        assertNull(activity.findViewById(R.id.authenticator_pairing_qr));
        assertFalse(copy.contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
        assertTrue(activity.findViewById(R.id.keyholder_pair_device).isShown());
        assertTrue(activity.findViewById(R.id.keyholder_pin_change_button).isShown());
        assertNull(activity.findViewById(R.id.keyholder_pair_button));
        checkTextFits(content);
    }
    private ActivityScenario<ContractActivity> launch() {
        return ActivityScenario.launch(ContractActivity.class);
    }
    private void assertAllowances() {
        assertEquals(Set.of("com.android.chrome", "com.android.settings"), new AppModeManager(context).getIncludedPackages());
        AppTimerManager timers = new AppTimerManager(context);
        assertEquals(35, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(75, timers.allowanceMinutes("com.android.settings"));
    }
    private static void awaitContract(ActivityScenario<ContractActivity> scenario) {
        long end = android.os.SystemClock.uptimeMillis() + 10000;
        AtomicBoolean ready = new AtomicBoolean();
        while (android.os.SystemClock.uptimeMillis() < end) {
            scenario.onActivity(activity -> {
                View body = activity.findViewById(R.id.role_page_body);
                AsyncUiScope data = AsyncUiScope.forPage(activity);
                ready.set(!data.isPending("contract")
                        && text(body).contains(activity.getString(R.string.atmosphere_title))
                        && body.isLaidOut() && body.getWidth() > 0 && body.getHeight() > 0
                        && body.findViewWithTag("person_contract_scope") != null);
            });
            if (ready.get()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitCommittedFrame();
                return;
            }
            android.os.SystemClock.sleep(25);
        }
        fail("Contract details did not finish loading and layout");
    }
    private void inspect(String file, java.util.function.Consumer<String> check) {
        awaitCommittedFrame();
        onView(withId(R.id.role_page_body)).check((body, missing) -> {
            if (missing != null) throw missing;
            check.accept(text(body));
            assertFalse(text(body).contains("SYNTHETIC_SECRET_DO_NOT_SHOW"));
            checkTextFits(body);
            assertFalse(hasConfigurationInput(body));
            assertNull(body.findViewById(R.id.person_session_action));
            assertNull(body.findViewWithTag("person_apps"));
            assertNull(body.findViewWithTag("person_duration"));
            View navigation = body.getRootView().findViewById(R.id.nav_contract);
            assertTrue(navigation.isShown());
            assertTrue(navigation.isSelected());
        });
        if (file != null) {
            Bitmap bitmap = renderedContractScreenshot(file);
            bitmap.recycle();
        }
    }
    private Bitmap renderedContractScreenshot(String file) {
        java.util.concurrent.atomic.AtomicReference<Rect> bounds = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger color = new java.util.concurrent.atomic.AtomicInteger();
        onView(withId(R.id.nav_contract_label)).check((label, missing) -> {
            if (missing != null) throw missing;
            int[] point = new int[2]; label.getLocationOnScreen(point);
            bounds.set(new Rect(point[0], point[1], point[0] + label.getWidth(), point[1] + label.getHeight()));
            color.set(((TextView) label).getCurrentTextColor());
        });
        long end = android.os.SystemClock.uptimeMillis() + 5000;
        boolean first = true;
        Bitmap last = null;
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (last != null) { last.recycle(); last = null; }
            Bitmap frame = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
            assertNotNull("Native compositor screenshot available", frame);
            Rect label = new Rect(bounds.get());
            label.inset(label.width() / 3, label.height() / 4);
            int matched = 0, expected = color.get();
            if (label.left >= 0 && label.top >= 0 && label.right <= frame.getWidth() && label.bottom <= frame.getHeight()) {
                for (int y = label.top; y < label.bottom; y++) for (int x = label.left; x < label.right; x++) {
                    int pixel = frame.getPixel(x, y);
                    if (sameOpaqueColor(pixel, expected)) matched++;
                }
            }
            if (matched > 40) {
                savePixels(frame, file);
                return frame;
            }
            if (first) {
                savePixels(frame, file.replace(".png", "-pending.png"));
                first = false;
            }
            last = frame;
            // A hardware frame callback alone does not establish compositor visibility.
            awaitCommittedFrame();
        }
        if (last != null) { savePixels(last, file.replace(".png", "-failed.png")); last.recycle(); }
        throw new AssertionError("Contract label absent from compositor at " + bounds.get());
    }
    private void savePixels(Bitmap bitmap, String name) {
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static boolean sameOpaqueColor(int actual, int expected) {
        // Allow two levels of 8-bit compositor rounding; fading remains a failed capture.
        return android.graphics.Color.alpha(actual) == 255
                && Math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) <= 2
                && Math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) <= 2
                && Math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) <= 2;
    }
    private static void awaitCommittedFrame() {
        java.util.concurrent.CountDownLatch frame = new java.util.concurrent.CountDownLatch(1);
        onView(withId(R.id.role_page_body)).check((body, missing) -> {
            if (missing != null) throw missing;
            View root = body.getRootView(); assertTrue(root.isHardwareAccelerated());
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown); root.invalidate();
        });
        try { assertTrue("Contract committed a native frame", frame.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void checkTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            assertNotNull("Text has a laid-out paragraph", label.getLayout());
            assertTrue("Text height: " + label.getText(), label.getLayout().getHeight()
                    <= label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom());
            for (int line = 0; line < label.getLayout().getLineCount(); line++)
                assertTrue("Text width: " + label.getText(), label.getLayout().getLineMax(line)
                        <= label.getWidth() - label.getCompoundPaddingLeft() - label.getCompoundPaddingRight() + 1);
        }
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            checkTextFits(((ViewGroup) view).getChildAt(index));
    }
    private static boolean hasConfigurationInput(View view) {
        if (view instanceof android.widget.EditText || view instanceof android.widget.CompoundButton
                || view instanceof android.widget.Spinner) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (hasConfigurationInput(((ViewGroup) view).getChildAt(index))) return true;
        return false;
    }
    private static String text(View view) {
        StringBuilder result = new StringBuilder();
        if (view instanceof TextView) result.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            result.append(text(((ViewGroup) view).getChildAt(i)));
        return result.toString();
    }

    private static boolean containsExact(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (containsExact(((ViewGroup) view).getChildAt(index), value)) return true;
        return false;
    }
}
