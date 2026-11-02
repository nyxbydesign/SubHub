package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.subhub.app.MainActivity;
import com.subhub.app.NativeUiActions;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityEvent;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.commitment.ServiceCountdownView;
import com.subhub.app.journal.JournalActivity;
import com.subhub.app.journal.JournalEntry;
import com.subhub.app.journal.JournalRuntime;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.R;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.GlobalSettingsActivity;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class OverviewReferenceAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> originals = new LinkedHashMap<>();
    private final Map<String, Boolean> journalFixtures = new LinkedHashMap<>();
    private final AtomicReference<List<PersonSnapshot>> people = new AtomicReference<>();
    private final AtomicReference<ProfileData> ownerOverride = new AtomicReference<>();
    private final AtomicReference<PersonSnapshot.SyncState> syncOverride = new AtomicReference<>();
    private final AtomicReference<List<PersonSnapshot.Allowance>> usage = new AtomicReference<>();
    private final AtomicInteger peerReads = new AtomicInteger();
    private ActivityRepository originalActivity;
    private JournalRuntime journal;
    private String domPrivate, subPrivate, domShared;

    @Before public void prepare() throws Exception {
        assertFalse("Owned inactive UI guest required", LocalSessionControls.isActive(context));
        for (String name : List.of(ProfileNames.STORE, FavoriteSubStore.PREFERENCES_NAME,
                "betablocker_stats", "betablocker_achievements", "subhub_service_duration_ui",
                "subhub_app_timer_usage", "subhub_penance"))
            originals.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterDomMode();
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        Set<String> earned = new java.util.LinkedHashSet<>();
        android.content.SharedPreferences.Editor achievementFixture = context.getSharedPreferences("betablocker_achievements", 0)
                .edit().remove("pending_notifications");
        for (boolean dom : new boolean[]{true, false}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            java.util.List<com.subhub.app.stats.AchievementManager.Achievement> catalog =
                    com.subhub.app.stats.AchievementManager.forWorkspace(context).all();
            for (int index = 0; index < 4; index++) {
                String id = catalog.get(index).getId(); earned.add(id);
                achievementFixture.putLong("unlocked_at." + id, System.currentTimeMillis() - 60_000L * (index + 1))
                        .remove("history_earned_at." + id);
            }
        }
        assertTrue(achievementFixture.putStringSet("unlocked", earned).commit());
        ControllerPinManager.enterDomMode();
        journal = JournalRuntime.get(context); recoverJournalFixtures();
        ControllerPinManager.enterDomMode();
        assertTrue(ProfileStore.save(context, true, "Nova", "Clear plans. Room to grow.", avatar(), "Woman", 34));
        ControllerPinManager.enterSubMode();
        assertTrue(ProfileStore.save(context, false, "Milo", "A steady routine and clear communication.", "", "Man", 29));
        Set<String> apps = Set.of("com.android.chrome", "com.android.vending");
        for (String app : apps) assertNotNull(context.getPackageManager().getApplicationInfo(app, 0));
        AppModeManager assignments = new AppModeManager(context); assignments.setAllAppsEnabled(false); assignments.saveIncludedPackages(apps);
        AppTimerManager timers = new AppTimerManager(context); timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(apps, Map.of("com.android.chrome", 30, "com.android.vending", 20));
        assertTrue(context.getSharedPreferences("subhub_service_duration_ui", 0).edit().putLong("duration", 7_200_000L)
                .putBoolean("hidden", false).commit());
        usage.set(List.of(new PersonSnapshot.Allowance("com.android.chrome", "Chrome", 720_000L, 30),
                new PersonSnapshot.Allowance("com.android.vending", "Play Store", 480_000L, 20)));
        People.installLocalProjection(this::project);
        people.set(List.of(remote('a', "Milo", "Man", 29, 1), remote('b', "Sage", "Non-binary", 26, 2)));
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { peerReads.incrementAndGet(); List<PersonId> ids = new ArrayList<>(); for (PersonSnapshot p : people.get()) ids.add(p.id); return ids; }
            public PersonSnapshot load(PersonId id) { peerReads.incrementAndGet(); for (PersonSnapshot p : people.get()) if (p.id.equals(id)) return p; throw new IllegalArgumentException("Missing fixture identity"); }
        });
        originalActivity = ActivitySources.repository();
        ActivitySources.install(new ActivityRepository() {
            public Page load(String before, int limit) { return new Page(events(limit), null); }
            public Page locate(String key, int limit) { return load(null, limit); }
            public List<ActivityEntry> recent(PersonId person, int limit) { return events(limit); }
            public int unreadCount() { return 0; }
            public void markRead(List<String> keys) { }
        });
        domShared = entry(true, "A shared plan for the day.", true, 3000);
        domPrivate = entry(true, "A good day to slow down.", false, 1000);
        subPrivate = entry(false, "A little progress today.", false, 1000);
        ControllerPinManager.enterDomMode();
        assertTrue(new FavoriteSubStore(context).toggle(people.get().get(0)));
    }

    @After public void restore() throws Exception {
        CommitmentManager.emergencyRelease(context); new AppModeManager(context).setArmed(false);
        deleteJournalFixtures();
        if (originalActivity != null) ActivitySources.install(originalActivity);
        for (Map.Entry<String, Map<String, ?>> value : originals.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(value.getKey(), 0), value.getValue());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void domOverviewUsesItsOwnJournalAndRecordedFactsAndOpensTheFavoriteIdentity() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> hasText(activity.findViewById(R.id.home_dom_overview), "A good day to slow down."));
            capture(activity, "overview-dom-top", null);
            capture(activity, "overview-dom-journal", "home_journal:dom");
            capture(activity, "overview-dom-recent", "home_recent_person:" + people.get().get(1).id.value());
            capture(activity, "overview-dom-activity", "activity_preview:all");
            capture(activity, "overview-dom-achievements", "achievement_preview");
            assertRecentBadges(activity);
            scenario.onActivity(a -> {
                View root = a.findViewById(R.id.home_dom_overview);
                assertFalse(hasText(root, "A little progress today."));
                assertFalse(hasText(root, "Pixel 8 Pro")); assertFalse(hasText(root, "On this device"));
                assertNotNull(root.findViewWithTag("activity_preview_entry:overview-fact-0"));
                assertNotNull(root.findViewWithTag("home_favorite:" + people.get().get(0).id.value()));
            });
            tap("home_journal:dom"); JournalActivity own = resumed(JournalActivity.class);
            assertTrue(own.getIntent().getBooleanExtra("journal_dom", false));
            assertEquals(PersonId.LOCAL.value(), own.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
            onMain(own::finish); resumed(MainActivity.class);
            tap("home_favorite_identity:" + people.get().get(0).id.value());
            PersonActivity selected = resumed(PersonActivity.class);
            assertEquals(people.get().get(0).id, PersonRoutes.requireId(selected.getIntent()));
            onMain(selected::finish); resumed(MainActivity.class); scenario.recreate();
            MainActivity recreated = resumed(MainActivity.class);
            await(() -> tagged(recreated, "home_favorite:" + people.get().get(0).id.value()) != null);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subOverviewUsesAuthorizedDomActualTimerOwnJournalAndConfiguredAppUsage() throws Exception {
        assertTrue(CommitmentManager.start(context, 5_040_000L, 5_040_000L, false));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> hasText(activity.findViewById(R.id.home_sub_summary), "A little progress today."));
            onMain(() -> activity.findViewById(R.id.home_service_widget).setTag("overview_session_card"));
            capture(activity, "overview-sub-top", null);
            capture(activity, "overview-sub-session", "overview_session_card");
            capture(activity, "overview-sub-journal", "home_journal:sub");
            capture(activity, "overview-sub-app-time", "home_app_time");
            capture(activity, "overview-sub-achievements", "achievement_preview");
            assertRecentBadges(activity);
            capture(activity, "overview-sub-help", "home_help");
            scenario.onActivity(a -> {
                assertFalse(a.findViewById(R.id.button_protection).isShown());
                View body = a.findViewById(R.id.home_sub_summary);
                assertFalse(hasText(body, "A good day to slow down."));
                assertTrue(hasText(body, "12 / 30 min")); assertTrue(hasText(body, "8 / 20 min"));
                assertEquals("Nova", ((TextView) tagged(a, "home_contract_dom_name")).getText().toString());
                assertTrue(((TextView) tagged(a, "home_contract_dom_details")).getText().toString().contains("34"));
                assertCanvasFits((ServiceCountdownView) a.findViewById(R.id.service_countdown));
                try {
                    TextView state = (TextView) tagged(a, "overview_session_state");
                    android.text.Layout layout = state.getLayout(); assertNotNull(layout);
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                    ((RoleHomePresenter) field.get(a)).renderOverviewSession(true, false);
                    assertSame("Unchanged clock ticks retain the laid-out session label", layout, state.getLayout());
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            tap("home_journal:sub"); JournalActivity own = resumed(JournalActivity.class);
            assertFalse(own.getIntent().getBooleanExtra("journal_dom", true));
            assertEquals(PersonId.LOCAL.value(), own.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
            onMain(own::finish); resumed(MainActivity.class);
            tap("home_assigned_dom_connection"); ContractActivity contract = resumed(ContractActivity.class);
            assertReadOnly(contract.findViewById(R.id.role_page_body));
            onMain(contract::finish); resumed(MainActivity.class);
            onView(withId(R.id.primary_header_settings)).perform(NativeUiActions.revealAboveNavigation(), click());
            GlobalSettingsActivity settings = resumed(GlobalSettingsActivity.class);
            onMain(settings::finish); resumed(MainActivity.class);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subContractKeepsAppsDurationExpandableFeaturesAndOnlyAuthorizedSharedJournal() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "contract_section:censor") != null);
            capture(activity, "contract-sub-top", null);
            capture(activity, "contract-sub-apps-duration", "person_contract_scope");
            onMain(() -> {
                View apps = tagged(activity, "contract_section:apps");
                assertTrue(hasText(apps, activity.getResources().getQuantityString(R.plurals.overview_apps_selected, 2, 2)));
                assertFalse(hasText(apps, "Chrome"));
                android.widget.ImageView arrow = (android.widget.ImageView) ((ViewGroup) apps).getChildAt(((ViewGroup) apps).getChildCount() - 1);
                assertEquals(90f, arrow.getRotation(), 0f);
            });
            tap("contract_section:apps");
            await(() -> tagged(activity, "contract_details:apps").getVisibility() == View.VISIBLE);
            capture(activity, "contract-sub-apps-expanded", "contract_details:apps");
            onMain(() -> {
                ViewGroup apps = (ViewGroup) tagged(activity, "contract_section:apps");
                assertEquals(-90f, apps.getChildAt(apps.getChildCount() - 1).getRotation(), 0f);
                assertTrue(hasText(tagged(activity, "contract_details:apps"), "Chrome"));
            });
            tap("contract_section:apps");
            capture(activity, "contract-sub-features", "person_contract_features");
            onMain(() -> {
                assertTrue(hasText(tagged(activity,"person_contract_identity"),activity.getString(R.string.overview_your_dom)));
                View header=tagged(activity,"contract_section:atmosphere");
                TextView summary=header.findViewWithTag("overview_row_value");
                assertTrue(summary.getText().toString().contains("\n"));
                assertTrue(summary.getLineCount()>=2);
            });
            scenario.onActivity(a -> {
                View body = a.findViewById(R.id.role_page_body); assertReadOnly(body);
                assertFalse(hasText(body, "A good day to slow down."));
                assertFalse(hasText(body, "A little progress today."));
                assertTrue(hasText(body, a.getString(R.string.overview_shared_journal)));
            });
            tap("contract_section:limits");
            await(() -> tagged(activity, "contract_details:limits").getVisibility() == View.VISIBLE);
            capture(activity, "contract-sub-limits-expanded", "contract_details:limits");
            tap("contract_section:atmosphere");
            onMain(() -> assertEquals(View.GONE, tagged(activity, "contract_details:limits").getVisibility()));
            scenario.recreate(); final ContractActivity recreated = resumed(ContractActivity.class);
            await(() -> tagged(recreated, "contract_details:atmosphere") != null
                    && tagged(recreated, "contract_details:atmosphere").getVisibility() == View.VISIBLE);
            capture(recreated, "contract-sub-atmosphere-restored", "contract_details:atmosphere");
            tap("profile_journal"); JournalActivity shared = resumed(JournalActivity.class);
            await(() -> tagged(shared, "journal_entry:" + domShared) != null);
            onMain(() -> {
                assertNull(tagged(shared, "journal_entry:" + domPrivate));
                assertNull(tagged(shared, "journal_entry:" + subPrivate));
                assertNull(tagged(shared, "journal_new"));
            });
            assertEquals("journal_local_dom", shared.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
            onMain(shared::finish); resumed(ContractActivity.class);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void unknownStalePendingAndUnknownUsageRemainExplicitWithoutZeroProgress() throws Exception {
        PersonSnapshot old = people.get().get(0);
        people.set(List.of(copy(old, new PersonSnapshot.SyncState(PersonSnapshot.Freshness.STALE,
                PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN, "wanted", "applied"), old.allowances)));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> tagged(activity, "home_favorite_unknown:" + old.id.value()) != null);
            capture(activity, "overview-dom-unknown", null);
            onMain(() -> {
                assertTrue(hasText(tagged(activity, "home_favorite_unknown:" + old.id.value()), activity.getString(R.string.overview_session_stats_unknown)));
                assertFalse(hasText(tagged(activity, "home_favorite:" + old.id.value()), "Protected 0"));
                assertNull(tagged(activity, "home_favorite_stats:" + old.id.value()));
                assertTrue(hasText(tagged(activity, "home_in_session"), activity.getString(R.string.overview_unknown)));
            });
        }
        usage.set(List.of(new PersonSnapshot.Allowance("com.android.chrome", "Chrome", -1, 35)));
        syncOverride.set(new PersonSnapshot.SyncState(PersonSnapshot.Freshness.STALE,
                PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "next", "old"));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> tagged(activity, "home_app_time") != null);
            capture(activity, "overview-sub-unknown-usage", "home_app_time");
            onMain(() -> {
                View card = tagged(activity, "home_app_time");
                assertTrue(hasText(card, activity.getString(R.string.role_allowance_unknown, 35)));
                assertFalse(hasText(card, "0 / 35 min")); assertFalse(hasType(card, android.widget.ProgressBar.class));
            });
        }
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "person_contract_scope") != null);
            capture(activity, "contract-sub-stale-pending", "person_contract_scope");
            onMain(() -> {
                assertTrue(hasText(tagged(activity, "person_contract_scope"), activity.getString(R.string.role_changes_pending)));
                assertTrue(hasText(tagged(activity, "person_contract_scope"), activity.getString(R.string.role_stale_data)));
            });
        }
        syncOverride.set(new PersonSnapshot.SyncState(PersonSnapshot.Freshness.UNKNOWN,
                PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "", ""));
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "person_contract_features") != null);
            capture(activity, "contract-sub-unknown-values", "person_contract_features");
            onMain(() -> {
                View features = tagged(activity, "person_contract_features");
                assertTrue(hasText(features, activity.getString(R.string.overview_unknown)));
                assertFalse(hasText(features, "0 Effects")); assertFalse(hasText(features, "Whispers Off"));
                assertNull(tagged(activity, "contract_details:atmosphere"));
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void appTimeShowsAllOnlyWhenAdditionalConfiguredAllowancesExistAndRestoresExpansion() throws Exception {
        java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>(List.of("com.android.chrome", "com.android.vending"));
        List<String> candidates = new ArrayList<>();
        for (android.content.pm.ApplicationInfo app : context.getPackageManager().getInstalledApplications(0))
            if (!app.packageName.equals(context.getPackageName()) && !selected.contains(app.packageName)
                    && context.getPackageManager().getLaunchIntentForPackage(app.packageName) != null) candidates.add(app.packageName);
        Collections.sort(candidates); assertTrue("Two additional installed launcher apps are required", candidates.size() >= 2);
        selected.add(candidates.get(0)); selected.add(candidates.get(1));
        new AppModeManager(context).saveIncludedPackages(selected);
        assertEquals(selected, new AppModeManager(context).getIncludedPackages());
        Map<String, Integer> limits = new LinkedHashMap<>(); List<PersonSnapshot.Allowance> projected = new ArrayList<>();
        int index = 0;
        for (String name : selected) {
            String label = context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(name, 0)).toString();
            int minutes = 20 + 10 * index; limits.put(name, minutes);
            projected.add(new PersonSnapshot.Allowance(name, label, (++index) * 60_000L, minutes));
        }
        new AppTimerManager(context).saveAllowances(selected, limits); usage.set(projected);
        String last = projected.get(3).label; ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> tagged(activity, "home_usage_all") != null);
            onMain(() -> assertFalse(hasText(tagged(activity, "home_app_time"), last)));
            capture(activity, "overview-sub-app-time-bounded", "home_app_time");
            tap("home_usage_all");
            await(() -> hasText(tagged(activity, "home_app_time"), last));
            capture(activity, "overview-sub-app-time-all", "home_app_time");
            scenario.recreate(); MainActivity recreated = resumed(MainActivity.class);
            await(() -> tagged(recreated, "home_usage_all") != null && hasText(tagged(recreated, "home_app_time"), last));
            tap("home_usage_all");
            await(() -> !hasText(tagged(recreated, "home_app_time"), last));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void hiddenPermanentAndInactiveSessionsPreserveClockAndStartVisibility() throws Exception {
        assertTrue(CommitmentManager.start(context, 5_040_000L, 5_040_000L, true));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> activity.findViewById(R.id.service_countdown).isShown());
            onMain(() -> activity.findViewById(R.id.home_service_widget).setTag("overview_session_card"));
            capture(activity, "overview-sub-hidden-timer", "overview_session_card");
            onMain(() -> {
                assertFalse(activity.findViewById(R.id.button_protection).isShown());
                assertEquals(activity.getString(R.string.pact_time_hidden), activity.findViewById(R.id.service_countdown).getContentDescription());
            });
        }
        CommitmentManager.emergencyRelease(context); new AppModeManager(context).setArmed(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> activity.findViewById(R.id.service_countdown).isShown());
            capture(activity, "overview-sub-permanent", null);
            onMain(() -> {
                assertFalse(activity.findViewById(R.id.button_protection).isShown());
                assertEquals(activity.getString(R.string.service_duration_permanent), activity.findViewById(R.id.service_countdown).getContentDescription());
            });
        }
        new AppModeManager(context).setArmed(false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity activity = resumed(MainActivity.class);
            await(() -> activity.findViewById(R.id.button_protection).isShown());
            onMain(() -> activity.findViewById(R.id.button_protection).setTag("overview_session_start"));
            capture(activity, "overview-sub-inactive", "overview_session_start");
            onMain(() -> assertFalse(activity.findViewById(R.id.service_countdown).isShown()));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void longAndMissingContractProfileFieldsKeepCompleteDetailsReachable() throws Exception {
        ownerOverride.set(new ProfileData("Alexandra Morgan with a long display name",
                "Clear communication and a steady routine. ".repeat(8).trim(), avatar(),
                "Non-binary / gender nonconforming person", 34));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "contract_dom_name") != null);
            capture(activity, "contract-sub-long-identity", "contract_dom_identity");
            tap("contract_dom_about_expand");
            capture(activity, "contract-sub-expanded-about-end", "contract_dom_about_expand");
            onMain(() -> {
                TextView name = (TextView) tagged(activity, "contract_dom_name");
                TextView details = (TextView) tagged(activity, "contract_dom_details");
                assertSame(name.getParent(), details.getParent()); assertEquals(name.getBottom(), details.getTop());
                assertTrue(details.getText().toString().contains(ownerOverride.get().gender));
            });
        }
        ownerOverride.set(ProfileData.empty(context.getString(R.string.role_dom)));
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "contract_dom_details") != null);
            capture(activity, "contract-sub-missing-profile", null);
            onMain(() -> assertEquals(context.getString(R.string.role_dom), ((TextView) tagged(activity, "contract_dom_details")).getText().toString()));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void sharedContractJournalLinkRechecksAudienceAndLockedDomDoesNotReadPeople() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = resumed(ContractActivity.class);
            await(() -> tagged(activity, "profile_journal") != null && tagged(activity, "profile_journal").isEnabled());
            assertFalse("Sub cannot change device capabilities", DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
            assertTrue(ControllerPinManager.verify(context, "2468"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.SUB_ONLY));
            ControllerPinManager.enterSubMode();
            await(() -> !tagged(activity, "profile_journal").isEnabled());
            capture(activity, "contract-sub-journal-unavailable", "person_contract_identity");
            onMain(() -> assertFalse(hasText(activity.findViewById(R.id.role_page_body), "A good day to slow down.")));
        }
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.DOM_ONLY));
        ControllerPinManager.enterSubMode();
        int before = peerReads.get();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            onView(withText(R.string.controller_pin_unlock_title)).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
            onView(withId(android.R.id.button2)).perform(click());
            MainActivity activity = resumed(MainActivity.class);
            await(() -> tagged(activity, "home_dom_unlock") != null);
            capture(activity, "overview-dom-locked", null);
            assertEquals(before, peerReads.get());
            onMain(() -> assertNull(tagged(activity, "home_journal:dom")));
        }
    }

    private PersonSnapshot project(PersonSnapshot local) {
        PersonSnapshot result = copy(local, syncOverride.get() == null ? local.sync : syncOverride.get(), usage.get());
        ProfileData profile = ownerOverride.get() == null ? local.contractDom : ownerOverride.get();
        return profile == null ? result : result.withContractDom(profile, local.contractDomId);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void sharedFeatureAndHelpPagesRenderCompactCardsAndTheirExistingHeaderActions() throws Exception {
        for (Class<? extends Activity> type : List.of(com.subhub.app.settings.SettingsActivity.class,
                com.subhub.app.appmode.AppModeActivity.class, com.subhub.app.penance.PenanceActivity.class,
                com.subhub.app.atmosphere.AtmosphereActivity.class, com.subhub.app.help.HelpActivity.class)) {
            ControllerPinManager.enterDomMode();
            try (ActivityScenario<? extends Activity> scenario = ActivityScenario.launch(type)) {
                Activity activity = resumed(type);
                await(() -> activity.findViewById(R.id.primary_header_title).isLaidOut()
                        && !activity.getWindow().getDecorView().isLayoutRequested());
                String label = "shared-page-" + type.getSimpleName();
                com.subhub.app.UiRefreshWitness.capture(activity, label);
                onMain(() -> {
                    TextView title = activity.findViewById(R.id.primary_header_title);
                    assertNotNull(title.getLayout());
                    for (int line = 0; line < title.getLayout().getLineCount(); line++)
                        assertEquals(0, title.getLayout().getEllipsisCount(line));
                    if (type == com.subhub.app.help.HelpActivity.class)
                        assertTrue(activity.findViewById(R.id.button_back).isShown());
                    else assertTrue(activity.findViewById(R.id.primary_header_settings).isShown());
                });
            }
        }
    }
    private static PersonSnapshot copy(PersonSnapshot p, PersonSnapshot.SyncState sync, List<PersonSnapshot.Allowance> allowances) {
        PersonSnapshot result = new PersonSnapshot(p.id, p.name, p.device, p.contractName, p.serviceLabel, p.connection,
                p.freshness, p.lastSyncedAt, p.capabilities, p.sections, allowances, p.currency, p.protectedSeconds,
                p.activityEvents, p.tributeCents, p.sessions, p.serviceActive, sync, p.sessionIdentity, p.additionOrder)
                .withProfile(p.profile, p.featureConsent, p.consentHardcoreOverride);
        return p.contractDom == null ? result : result.withContractDom(p.contractDom, p.contractDomId);
    }
    private PersonSnapshot remote(char key, String name, String gender, int age, long order) {
        PersonId id = new PersonId("remote_" + String.valueOf(key).repeat(64) + "_overview");
        return new PersonSnapshot(id, name, "Pixel 8 Pro", "Current configuration", "Session active",
                PersonSnapshot.Connection.CONNECTED, PersonSnapshot.Freshness.CURRENT, System.currentTimeMillis(),
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS),
                List.of(), List.of(), "EUR", 45_600, 48, 0, 3, true,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT,
                        PersonSnapshot.Freshness.CURRENT, "", ""), "session", order)
                .withProfile(new ProfileData(name, "", "", gender, age), Collections.emptyMap());
    }
    private List<ActivityEntry> events(int limit) {
        long now = System.currentTimeMillis() - 180_000L;
        List<ActivityEntry> values = List.of(new ActivityEntry("overview-fact-0", people.get().get(0).id, "Milo", "Pixel 8 Pro",
                new ActivityEvent("overview-0", ActivityEvent.Kind.APP_OPENED, now, "com.android.chrome", 0, "", "", 0), now, false, "Chrome"),
                new ActivityEntry("overview-fact-1", people.get().get(0).id, "Milo", "Pixel 8 Pro",
                        new ActivityEvent("overview-1", ActivityEvent.Kind.APP_LIMIT_REACHED, now - 240_000L, "com.android.vending", 0, "", "", 20), now, false, "Play Store"));
        return values.subList(0, Math.min(limit, values.size()));
    }
    private String entry(boolean dom, String text, boolean share, long ago) throws Exception {
        if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
        String id = UUID.randomUUID().toString().replace("-", ""); journalFixtures.put(id, dom); writeLedger();
        long now = System.currentTimeMillis() - ago;
        journal.save(dom, new JournalEntry(id, now, now, text, null, List.of()), share, share); return id;
    }
    private File ledger() { return new File(context.getCacheDir(), "overview-reference-journal-fixtures.json"); }
    private void writeLedger() throws Exception { Files.write(ledger().toPath(), new org.json.JSONObject(journalFixtures).toString().getBytes(StandardCharsets.UTF_8)); }
    private void recoverJournalFixtures() throws Exception {
        if (!ledger().isFile()) return;
        org.json.JSONObject saved = new org.json.JSONObject(new String(Files.readAllBytes(ledger().toPath()), StandardCharsets.UTF_8));
        java.util.Iterator<String> ids = saved.keys();
        while (ids.hasNext()) { String id = ids.next(); assertTrue(id.matches("[0-9a-f]{32}")); journalFixtures.put(id, saved.getBoolean(id)); }
        deleteJournalFixtures();
    }
    private void deleteJournalFixtures() throws Exception {
        if (journal == null) return;
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        for (String id : new ArrayList<>(journalFixtures.keySet())) {
            boolean dom = journalFixtures.get(id);
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            journal.delete(dom, id); journalFixtures.remove(id); writeLedger();
        }
        Files.deleteIfExists(ledger().toPath());
    }
    private String avatar() throws java.io.IOException {
        Bitmap image = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888); image.eraseColor(Color.rgb(118, 80, 153));
        android.graphics.Canvas canvas = new android.graphics.Canvas(image);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); paint.setTextSize(96);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("N", 96, 96 - (paint.ascent() + paint.descent()) / 2, paint);
        try { return ProfileImages.encode(image); } finally { image.recycle(); }
    }
    private Intent home() { return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
            .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true); }
    private static void tap(String tag) { onView(withTagValue(is(tag))).perform(NativeUiActions.revealAboveNavigation(), click()); }
    private static View tagged(Activity activity, String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private static boolean hasText(View view, String text) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasText(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private static boolean hasType(View view, Class<?> type) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (type.isInstance(view)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) if (hasType(((ViewGroup) view).getChildAt(i), type)) return true;
        return false;
    }
    private static void assertReadOnly(View root) { assertFalse(hasType(root, EditText.class)); assertFalse(hasType(root, android.widget.CompoundButton.class)); }
    private static void assertRecentBadges(Activity activity) {
        onMain(() -> {
            ViewGroup badges = (ViewGroup) tagged(activity, "achievement_recent_badges");
            assertNotNull(badges); assertEquals(3, badges.getChildCount());
            TextView next = ((ViewGroup) badges.getParent()).findViewWithTag("achievement_next");
            assertNotNull(next); assertTrue(next.getText().length() > 0);
            com.subhub.app.stats.AchievementManager manager = com.subhub.app.stats.AchievementManager.forWorkspace(activity);
            java.util.List<com.subhub.app.stats.AchievementManager.Achievement> earnedBadges = new ArrayList<>();
            for (com.subhub.app.stats.AchievementManager.Achievement badge : manager.all())
                if (manager.isUnlocked(badge.getId())) earnedBadges.add(badge);
            earnedBadges.sort(java.util.Comparator.comparingLong(
                    (com.subhub.app.stats.AchievementManager.Achievement badge) -> manager.getUnlockedAt(badge.getId())).reversed());
            long previous = Long.MAX_VALUE;
            for (int index = 0; index < badges.getChildCount(); index++) {
                View badge = ((ViewGroup) badges.getChildAt(index)).getChildAt(0);
                String id = badge.getTag().toString().substring("achievement_recent:".length());
                assertTrue(manager.isUnlocked(id));
                TextView title = ((ViewGroup) badges.getChildAt(index)).findViewWithTag("achievement_recent_title:" + id);
                assertNotNull(title);
                assertEquals(activity.getString(earnedBadges.get(index).getName()), title.getText().toString());
                assertEquals(earnedBadges.get(index).getId(), id);
                long earned = manager.getUnlockedAt(id); assertTrue(earned <= previous); previous = earned;
            }
        });
    }
    private static void assertCanvasFits(ServiceCountdownView clock) {
        try {
            java.lang.reflect.Field p = ServiceCountdownView.class.getDeclaredField("text"), l = ServiceCountdownView.class.getDeclaredField("overviewLabel");
            p.setAccessible(true); l.setAccessible(true); Paint paint = (Paint) p.get(clock);
            assertTrue(paint.measureText((String) l.get(clock)) <= clock.getWidth() + 1);
            assertTrue(paint.descent() - paint.ascent() <= clock.getHeight() + 1);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void capture(Activity activity, String label, String target) throws Exception {
        if (activity instanceof MainActivity) {
            AtomicReference<Boolean> notice = new AtomicReference<>(false);
            onMain(() -> notice.set(activity.findViewById(R.id.global_notice_host).isShown()));
            if (notice.get()) {
                Context context = activity.getApplicationContext();
                String services = android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
                String accessibility = android.provider.Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
                int notifications = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS);
                onView(withId(R.id.global_notice_dismiss)).perform(NativeUiActions.revealAboveNavigation(), click());
                await(() -> !activity.findViewById(R.id.global_notice_host).isShown());
                assertEquals(services, android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
                assertEquals(accessibility, android.provider.Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
                assertEquals(notifications, context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
            }
        }
        ProfileStatsActivityAndroidTest.captureNativeProfile(activity, label, target);
    }
    private static void onMain(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private static void await(BooleanSupplier ready) {
        long until = SystemClock.uptimeMillis() + 10_000L;
        while (SystemClock.uptimeMillis() < until) { AtomicReference<Boolean> value = new AtomicReference<>(false); onMain(() -> value.set(ready.getAsBoolean())); if (value.get()) return; SystemClock.sleep(30); }
        fail("Overview native state did not settle");
    }
    private static <T extends Activity> T resumed(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        await(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                if (type.isInstance(activity) && activity.hasWindowFocus()) result.set(type.cast(activity));
            return result.get() != null;
        }); return result.get();
    }
}
