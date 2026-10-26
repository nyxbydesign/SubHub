package com.subhub.app.people;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.commitment.ServiceCountdownView;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.studio.StudioActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RoleExperienceAndroidTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private String originalServices, originalAccessibility;
    private int tapSequence;

    @Before public void prepare() {
        originalServices = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        originalAccessibility = Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        automation();
        for (String name : List.of(SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_home",
                "subhub_service_duration_ui", "subhub_app_timer_usage", "betablocker_stats", "betablocker_achievements")) {
            original.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        }
        People.installRemoteRepository(null);
        People.installRemoteAuthority(() -> false);
        People.installConnectionUi(null);
        OnboardingState.complete(context);
        Set<String> apps = new LinkedHashSet<>(List.of("com.android.chrome", "com.android.vending"));
        for (String app : apps) {
            try { context.getPackageManager().getApplicationInfo(app, 0); }
            catch (Exception missing) { throw new AssertionError("Capture fixture app is missing: " + app, missing); }
        }
        new AppModeManager(context).saveIncludedPackages(apps);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(apps, Map.of("com.android.chrome", 20, "com.android.vending", 10));
        assertEquals(apps, new AppModeManager(context).getIncludedPackages());
        assertEquals(20, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(10, timers.allowanceMinutes("com.android.vending"));
    }

    @After public void restore() throws Exception {
        People.installRemoteRepository(null);
        People.installRemoteAuthority(() -> false);
        People.installConnectionUi(null);
        ControllerPinManager.enterSubMode();
        for (Map.Entry<String, Map<String, ?>> saved : original.entrySet()) {
            SharedPreferences.Editor edit = context.getSharedPreferences(saved.getKey(), 0).edit().clear();
            for (Map.Entry<String, ?> item : saved.getValue().entrySet()) put(edit, item.getKey(), item.getValue());
            assertTrue(edit.commit());
        }
        automation();
        restoreSecure("enabled_accessibility_services", originalServices);
        restoreSecure("accessibility_enabled", originalAccessibility);
        assertEquals(originalServices, Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
        assertEquals(originalAccessibility, Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subNavigationShowsReadOnlyContractStatsAndSafeSettings() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            await(() -> visibleChildCount(MainActivity.class, R.id.home_sub_summary) >= 2);
            MainActivity home = resumed(MainActivity.class);
            capture(home, "sub-overview");
            onMain(() -> {
                assertVisibleTabs(home, R.id.nav_home, R.id.nav_contract, R.id.nav_stats, R.id.nav_settings);
                assertFalse(home.findViewById(R.id.commitment_start_panel).isShown());
                assertFalse(home.findViewById(R.id.sub_dashboard).isShown());
                assertTrue(home.findViewById(R.id.button_protection).isShown());
                assertTrue(home.findViewById(R.id.button_edit_lock).isShown());
                assertFits(home.getWindow().getDecorView());
            });
            tap(home, R.id.nav_contract);
            ContractActivity contract = resumed(ContractActivity.class);
            await(() -> visibleChildCount(ContractActivity.class, R.id.role_page_body) >= 4);
            capture(contract, "sub-contract");
            onMain(() -> {
                assertFalse(hasInput(contract.findViewById(R.id.role_page_body)));
                assertTrue(contract.findViewById(R.id.nav_contract).isSelected());
                assertFits(contract.getWindow().getDecorView());
            });
            tap(contract, R.id.nav_stats);
            StatsActivity stats = resumed(StatsActivity.class);
            capture(stats, "sub-stats");
            onMain(() -> assertTrue(stats.findViewById(R.id.nav_stats).isSelected()));
            tap(stats, R.id.nav_settings);
            GlobalSettingsActivity settings = resumed(GlobalSettingsActivity.class);
            capture(settings, "sub-settings");
            onMain(() -> {
                assertFalse(settings.findViewById(R.id.apps_card).isShown());
                assertFalse(settings.findViewById(R.id.hardcore_card).isShown());
                assertFalse(settings.findViewById(R.id.privacy_discreet_toggle).isShown());
                assertTrue(settings.findViewById(R.id.button_help).isShown());
                assertFits(settings.getWindow().getDecorView());
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void domNavigatesThroughPeopleAndConfiguresDurationThere() throws Exception {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            await(() -> visibleChildCount(MainActivity.class, R.id.home_dom_overview) >= 2);
            MainActivity home = resumed(MainActivity.class);
            capture(home, "dom-overview");
            onMain(() -> {
                assertVisibleTabs(home, R.id.nav_home, R.id.nav_people, R.id.nav_settings);
                assertFalse(home.findViewById(R.id.home_service_widget).isShown());
                assertTrue(home.findViewById(R.id.button_edit_lock).isShown());
                assertFits(home.getWindow().getDecorView());
            });
            tap(home, R.id.nav_people);
            PeopleActivity people = resumed(PeopleActivity.class);
            await(() -> visibleChildCount(PeopleActivity.class, R.id.role_page_body) > 0
                    && bodyContains(people, context.getString(R.string.role_this_device)));
            capture(people, "dom-people");
            onMain(() -> PersonRoutes.open(people, PersonId.LOCAL));
            PersonActivity person = resumed(PersonActivity.class);
            await(() -> hasTag(person, "person_duration:local"));
            capture(person, "dom-person-contracts");
            tap(person, R.id.commitment_timer_1h);
            assertEquals(3_600_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
            tap(person, R.id.button_edit_lock);
            resumed(MainActivity.class);
            assertFalse(ControllerPinManager.isDomModeActive());
            onMain(() -> {
                // Even an already-delivered stale listener must no longer persist a choice.
                person.findViewById(R.id.commitment_timer_24h).performClick();
            });
            assertEquals(3_600_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subCannotEnterStudioOrEditByReopeningAnOldPersonPage() {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            resumed(MainActivity.class);
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
            assertFalse(ControllerPinManager.canEditConfiguration());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remotePersonCannotFallThroughToThisDevicesEditors() throws Exception {
        PersonId remoteId = new PersonId("peer:synthetic-alex");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(remoteId); }
            public PersonSnapshot load(PersonId id) {
                assertEquals(remoteId, id);
                return remote(id);
            }
        });
        ControllerPinManager.enterDomMode();
        Map<String, ?> before = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, remoteId.value()))) {
            PersonActivity person = resumed(PersonActivity.class);
            await(() -> bodyContains(person, "Synthetic Alex"));
            capture(person, "remote-person-offline-fixture");
            onMain(() -> {
                assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration:" + remoteId.value()));
                PersonRoutes.editSection(person, remoteId, "censor");
                assertTrue(ControllerPinManager.isDomModeActive());
            });
            assertEquals(before, new SettingsRepository(context).preferences().getAll());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void pairedAuthorityDeniesOrdinaryLocalConfigurationEvenInDom() throws Exception {
        People.installRemoteAuthority(() -> true);
        ControllerPinManager.enterDomMode();
        assertFalse(ControllerPinManager.canEditConfiguration());
        assertFalse(new LocalPersonRepository(context).load(PersonId.LOCAL).can(PersonSnapshot.Capability.EDIT_CONTRACT));
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            PersonActivity person = resumed(PersonActivity.class);
            await(() -> bodyContains(person, context.getString(R.string.role_active_contract)));
            onMain(() -> assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration:local")));
        }
        try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(StudioActivity.class)) {
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void durationRecreationUsesStoredConfigurationInBothDirections() {
        ControllerPinManager.enterDomMode();
        for (boolean savedWidget : new boolean[] {false, true}) {
            try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
                PersonActivity person = resumed(PersonActivity.class);
                await(() -> hasTag(person, "person_duration:local"));
                onMain(() -> ((android.widget.CompoundButton) person.findViewById(R.id.service_duration_hide)).setChecked(savedWidget));
                assertTrue(context.getSharedPreferences("subhub_service_duration_ui", 0).edit()
                        .putBoolean("hidden", !savedWidget).commit());
                scenario.recreate();
                PersonActivity recreated = resumed(PersonActivity.class);
                await(() -> hasTag(recreated, "person_duration:local"));
                onMain(() -> assertEquals(!savedWidget,
                        ((android.widget.CompoundButton) recreated.findViewById(R.id.service_duration_hide)).isChecked()));
                assertEquals(!savedWidget, context.getSharedPreferences("subhub_service_duration_ui", 0).getBoolean("hidden", savedWidget));
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void countdownCanvasTextFitsItsActualNativeInterior() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> visibleChildCount(ContractActivity.class, R.id.role_page_body) >= 4);
            AtomicReference<ServiceCountdownView> witness = new AtomicReference<>();
            onMain(() -> {
                LinearLayout body = activity.findViewById(R.id.role_page_body);
                body.removeAllViews();
                ServiceCountdownView view = new ServiceCountdownView(activity, null);
                view.setCountdown(2_592_000_000L, 2_592_000_000L, false, false);
                body.addView(view, new LinearLayout.LayoutParams(-1, PersonUi.dp(activity, 180)));
                witness.set(view);
            });
            capture(activity, "countdown-canvas");
            onMain(() -> {
                try {
                    java.lang.reflect.Field paint = ServiceCountdownView.class.getDeclaredField("text");
                    java.lang.reflect.Field center = ServiceCountdownView.class.getDeclaredField("center");
                    java.lang.reflect.Field label = ServiceCountdownView.class.getDeclaredField("label");
                    paint.setAccessible(true); center.setAccessible(true); label.setAccessible(true);
                    Paint actual = (Paint) paint.get(witness.get());
                    RectF region = (RectF) center.get(witness.get());
                    assertTrue(actual.measureText((String) label.get(witness.get())) <= region.width() + 1f);
                    assertTrue(actual.descent() - actual.ascent() <= region.height() + 1f);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
        }
    }

    private Intent home() {
        return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
    }

    private PersonSnapshot remote(PersonId id) {
        return new PersonSnapshot(id, "Synthetic Alex", "Pixel 8", "Evening contract", "Service active",
                PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.STALE, 1_700_000_000_000L,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.EDIT_CONTRACT,
                        PersonSnapshot.Capability.VIEW_STATS),
                List.of(new PersonSnapshot.Section("censor", "Censor", "8 filters", "Saved configuration")),
                Collections.emptyList(), "EUR", 3600, 8, 0, 1, true,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.STALE, PersonSnapshot.Freshness.CURRENT,
                        PersonSnapshot.Freshness.UNKNOWN, "revision-2", "revision-1"));
    }

    private UiAutomation automation() {
        return instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
    }

    private void tap(Activity activity, int id) throws Exception {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        onMain(() -> {
            View view = activity.findViewById(id);
            assertNotNull(view);
            view.requestRectangleOnScreen(new Rect(0, 0, view.getWidth(), view.getHeight()), true);
        });
        instrumentation.waitForIdleSync();
        onMain(() -> {
            View target = activity.findViewById(id);
            View navigation = activity.findViewById(R.id.bottom_navigation);
            Rect targetBounds = new Rect(), navBounds = new Rect();
            target.getGlobalVisibleRect(targetBounds);
            if (navigation != null && navigation.getGlobalVisibleRect(navBounds)
                    && targetBounds.bottom > navBounds.top && !isDescendant(target, navigation)) {
                android.view.ViewParent ancestor = target.getParent();
                while (ancestor instanceof View && !(ancestor instanceof android.widget.ScrollView)) ancestor = ancestor.getParent();
                if (ancestor instanceof android.widget.ScrollView) ((android.widget.ScrollView) ancestor)
                        .scrollBy(0, targetBounds.bottom - navBounds.top + PersonUi.dp(activity, 12));
            }
        });
        instrumentation.waitForIdleSync();
        capture(activity, "tap-" + (++tapSequence) + "-" + activity.getResources().getResourceEntryName(id));
        onMain(() -> {
            Rect rect = new Rect();
            assertTrue(activity.findViewById(id).getGlobalVisibleRect(rect));
            View nav = activity.findViewById(R.id.bottom_navigation);
            if (nav != null && !isDescendant(activity.findViewById(id), nav)) {
                Rect navBounds = new Rect();
                assertTrue(nav.getGlobalVisibleRect(navBounds));
                assertTrue("Target is obscured by navigation", rect.bottom <= navBounds.top);
            }
            bounds.set(rect);
        });
        Rect rect = bounds.get();
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "candidate-1");
        org.json.JSONObject point = new org.json.JSONObject();
        point.put("resource", activity.getResources().getResourceName(id));
        point.put("bounds", new org.json.JSONArray(new int[] {rect.left, rect.top, rect.right, rect.bottom}));
        point.put("x", rect.exactCenterX()); point.put("y", rect.exactCenterY());
        point.put("source", "SOURCE_TOUCHSCREEN");
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(),
                "ui-v3/" + run + "/tap-" + tapSequence + "-point.json"))) {
            output.write(point.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation().injectInputEvent(down, true)); assertTrue(automation().injectInputEvent(up, true)); }
        finally { down.recycle(); up.recycle(); }
        instrumentation.waitForIdleSync();
    }

    private void capture(Activity activity, String name) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new AssertionError("Frame commit needs API 29");
        await(() -> {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            onMain(() -> {
                View root = activity.getWindow().getDecorView();
                boolean settled = activity.hasWindowFocus() && !root.isLayoutRequested();
                ViewGroup nav = activity.findViewById(R.id.bottom_navigation);
                if (nav != null) for (int i = 0; i < nav.getChildCount(); i++) {
                    View child = nav.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE && (child.getWidth() == 0 || child.getHeight() == 0)) settled = false;
                }
                ready.set(settled);
            });
            return ready.get();
        });
        CountDownLatch frame = new CountDownLatch(1);
        onMain(() -> {
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);
            root.invalidate();
        });
        assertTrue("No hardware frame committed", frame.await(5, TimeUnit.SECONDS));
        Bitmap pixels = automation().takeScreenshot();
        assertNotNull(pixels);
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "candidate-1");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new AssertionError("Invalid evidence namespace");
        File folder = new File(context.getFilesDir(), "ui-v3/" + run);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        String scale = Float.toString(context.getResources().getConfiguration().fontScale);
        int width = activity.getResources().getConfiguration().screenWidthDp;
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + "-" + scale + "-" + width + ".png"))) {
            assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { pixels.recycle(); }
    }

    private <T extends Activity> T resumed(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        await(() -> {
            onMain(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (type.isInstance(activity)) result.set(type.cast(activity));
            });
            return result.get() != null;
        });
        return result.get();
    }

    private int visibleChildCount(Class<? extends Activity> type, int id) {
        AtomicReference<Integer> count = new AtomicReference<>(0);
        onMain(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (type.isInstance(activity)) {
                    ViewGroup view = activity.findViewById(id);
                    if (view != null) count.set(view.getChildCount());
                }
            }
        });
        return count.get();
    }

    private boolean hasTag(Activity activity, String tag) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        onMain(() -> found.set(activity.getWindow().getDecorView().findViewWithTag(tag) != null));
        return found.get();
    }

    private boolean bodyContains(Activity activity, String value) {
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        onMain(() -> found.set(contains(activity.findViewById(R.id.role_page_body), value)));
        return found.get();
    }

    private static boolean contains(View view, String value) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(value)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), value)) return true;
        return false;
    }

    private void await(BooleanSupplier ready) {
        long end = SystemClock.uptimeMillis() + 8_000L;
        do {
            instrumentation.waitForIdleSync();
            if (ready.getAsBoolean()) return;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("UI did not reach its expected state");
    }

    private void onMain(Runnable action) { instrumentation.runOnMainSync(action); }

    private static void assertVisibleTabs(Activity activity, int... expected) {
        Set<Integer> actual = new LinkedHashSet<>();
        ViewGroup nav = activity.findViewById(R.id.bottom_navigation);
        for (int i = 0; i < nav.getChildCount(); i++) if (nav.getChildAt(i).getVisibility() == View.VISIBLE)
            actual.add(nav.getChildAt(i).getId());
        Set<Integer> wanted = new LinkedHashSet<>();
        for (int id : expected) wanted.add(id);
        assertEquals(wanted, actual);
    }

    private static boolean hasInput(View view) {
        if (view instanceof EditText && view.isShown()) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasInput(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }

    private static void assertFits(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null) {
                int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
                for (int line = 0; line < text.getLineCount(); line++) {
                    assertEquals("Ellipsized text: " + text.getText(), 0, text.getLayout().getEllipsisCount(line));
                    assertTrue("Drawn text too wide: " + text.getText() + " line=" + line
                            + " extent=" + text.getLayout().getLineMax(line) + " width=" + width,
                            text.getLayout().getLineMax(line) <= width + 1);
                }
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertFits(((ViewGroup) view).getChildAt(i));
    }

    private void restoreSecure(String key, String value) throws Exception {
        if (value != null && !value.matches("[A-Za-z0-9_./:$-]*")) throw new AssertionError("Unsupported original setting");
        if ("".equals(value)) {
            automation().adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
            try { assertTrue(Settings.Secure.putString(context.getContentResolver(), key, "")); }
            finally { automation().dropShellPermissionIdentity(); }
            return;
        }
        String command = value == null ? "settings delete secure " + key
                : "settings put secure " + key + " " + value;
        try (ParcelFileDescriptor result = automation().executeShellCommand(command);
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            byte[] bytes = new byte[256];
            while (input.read(bytes) != -1) { }
        }
    }

    private static boolean isDescendant(View child, View parent) {
        android.view.ViewParent ancestor = child.getParent();
        while (ancestor instanceof View) {
            if (ancestor == parent) return true;
            ancestor = ancestor.getParent();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor edit, String key, Object value) {
        if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else if (value instanceof Float) edit.putFloat(key, (Float) value);
        else if (value instanceof String) edit.putString(key, (String) value);
        else if (value instanceof Set) edit.putStringSet(key, new LinkedHashSet<>((Set<String>) value));
        else throw new AssertionError("Unsupported preference type");
    }
}
