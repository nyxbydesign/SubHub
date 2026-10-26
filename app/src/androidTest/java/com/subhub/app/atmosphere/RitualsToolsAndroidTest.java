package com.subhub.app.atmosphere;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.pack.SubHubPack;
import com.subhub.app.pack.SubHubPackManager;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.people.PersonActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.stats.StatsActivity;
import com.subhub.app.stats.AchievementsActivity;
import com.subhub.app.studio.StudioActivity;

import org.junit.*;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Disposable emulator fixtures; no live media, credentials or pack catalog. */
public final class RitualsToolsAndroidTest {
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private Context app;
    private SubHubPackManager packs;
    private String fixture;
    private final Map<SharedPreferences, Map<String, ?>> original = new LinkedHashMap<>();

    @Before
    public void setup() throws Exception {
        assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"));
        app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        for (String name : new String[] {SettingsRepository.PREFERENCES_NAME, "subhub_onboarding",
                "subhub_app_timer_usage", "betablocker_achievements", "betablocker_stats"}) {
            SharedPreferences prefs = app.getSharedPreferences(name, 0);
            original.put(prefs, new LinkedHashMap<>(prefs.getAll()));
        }
        OnboardingState.complete(app);
        ControllerPinManager.setPin(app, "2468");
        ControllerPinManager.enterDomMode();
        packs = new SubHubPackManager(app);
        SubHubPack pack = packs.createBlank();
        pack.setMetadata("Focus", "Sample", "", "1.0.0");
        packs.addToLibrary(pack);
        fixture = pack.getId();
        Set<String> selected = Set.of("com.android.chrome", "com.android.settings");
        for (String name : selected) app.getPackageManager().getApplicationInfo(name, 0);
        AppModeManager assignments = new AppModeManager(app);
        assignments.setAllAppsEnabled(false);
        assignments.saveIncludedPackages(selected);
        AppTimerManager timers = new AppTimerManager(app);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(selected, Map.of("com.android.chrome", 35, "com.android.settings", 75));
        assertEquals(selected, assignments.getIncludedPackages());
        assertEquals(35, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(75, timers.allowanceMinutes("com.android.settings"));
    }

    @After
    public void cleanup() {
        if (fixture != null) {
            packs.deleteLibrary(fixture);
            packs.deleteDraft(fixture);
        }
        for (Map.Entry<SharedPreferences, Map<String, ?>> saved : original.entrySet())
            SharedPreferenceTestRestore.restore(saved.getKey(), saved.getValue());
        ControllerPinManager.enterSubMode();
    }

    @Test
    public void savedContractsOpenFromTheirPersonAndStudioReturnsToContract() throws Exception {
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(
                new Intent(app, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            awaitTag(scenario, "person_tab:contracts");
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                    org.hamcrest.Matchers.is("person_tab:contracts"))).perform(
                    com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            awaitTag(scenario, "person_saved_contracts");
            scenario.onActivity(a -> capture(a, "person-contract-library.png"));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                    org.hamcrest.Matchers.is("person_saved_contracts"))).perform(
                    com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            StudioActivity studio = resumed(StudioActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals(PersonId.LOCAL, PersonRoutes.requireId(studio.getIntent()));
                assertTrue(studio.findViewById(R.id.nav_people).isSelected());
                assertFalse(studio.getIntent().getBooleanExtra(StudioActivity.EXTRA_FROM_RITUALS, false));
                studio.findViewById(R.id.button_back).performClick();
            });
            awaitTag(scenario, "person_saved_contracts");
            scenario.onActivity(a -> {
                assertEquals(app.getString(R.string.role_contract), ((TextView) a.findViewById(R.id.primary_header_title)).getText().toString());
                assertNull(a.findViewById(R.id.person_session_action));
                assertTrue(a.findViewById(R.id.nav_people).isSelected());
                capture(a, "person-contract-library-return.png");
            });
        }
    }

    @Test
    public void importShortcutUsesTheExistingPickerAndSubCanReadAchievements() throws Exception {
        Intents.init();
        try {
            Intents.intending(
                            androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                                    Intent.ACTION_OPEN_DOCUMENT))
                    .respondWith(
                            new android.app.Instrumentation.ActivityResult(
                                    Activity.RESULT_CANCELED, null));
            try (ActivityScenario<StudioActivity> scenario = ActivityScenario.launch(new Intent(app, StudioActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value())
                    .putExtra(StudioActivity.EXTRA_IMPORT_PACK, true))) {
                idle();
                Intents.intended(
                        androidx.test.espresso.intent.matcher.IntentMatchers.hasAction(
                                Intent.ACTION_OPEN_DOCUMENT));
            }
        } finally {
            Intents.release();
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<StatsActivity> scenario = ActivityScenario.launch(StatsActivity.class)) {
            awaitView(scenario, R.id.stats_achievements);
            scenario.onActivity(
                    a -> {
                        assertTrue(a.findViewById(R.id.nav_stats).isSelected());
                        assertEquals(View.GONE, a.findViewById(R.id.nav_atmosphere).getVisibility());
                    });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.stats_achievements))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.click());
            AchievementsActivity achievements = resumed(AchievementsActivity.class);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertFalse(ControllerPinManager.isDomModeActive());
                assertTrue(((ViewGroup) achievements.findViewById(R.id.achievement_list)).getChildCount() > 0);
                capture(achievements, "stats-sub-achievements.png");
            });
        }
    }

    private void awaitTag(ActivityScenario<PersonActivity> scenario, String tag) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        java.util.concurrent.atomic.AtomicBoolean loaded =
                new java.util.concurrent.atomic.AtomicBoolean();
        do {
            idle();
            scenario.onActivity(a -> loaded.set(a.findViewById(android.R.id.content).findViewWithTag(tag) != null));
        } while (!loaded.get() && android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("Person destination did not load: " + tag, loaded.get());
    }

    private void awaitView(ActivityScenario<? extends Activity> scenario, int id) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
        do { idle(); scenario.onActivity(a -> loaded.set(a.findViewById(id) != null)); }
        while (!loaded.get() && android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("Destination did not load", loaded.get());
    }

    private <T extends Activity> T resumed(Class<T> type) {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        do {
            idle();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (type.isInstance(activity)) result.set(type.cast(activity));
            });
        } while (result.get() == null && android.os.SystemClock.elapsedRealtime() < deadline);
        assertNotNull("Destination not resumed: " + type.getSimpleName(), result.get());
        return result.get();
    }

    private static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(120);
    }

    private static void capture(Activity activity, String name) {
        View root = activity.getWindow().getDecorView();
        Bitmap frame =
                Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(frame));
        try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), name))) {
            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, out));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            frame.recycle();
        }
    }
}
