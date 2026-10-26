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
    @org.junit.Rule public final org.junit.rules.TestName testName = new org.junit.rules.TestName();
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
                "subhub_service_duration_ui", "subhub_app_timer_usage", "subhub_penance",
                "betablocker_stats", "betablocker_achievements")) {
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
        com.subhub.app.commitment.CommitmentManager.emergencyRelease(context);
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
            await(() -> scenario.getState() == Lifecycle.State.DESTROYED);
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            resumed(MainActivity.class);
            await(() -> scenario.getState() == Lifecycle.State.DESTROYED);
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

    @Test @SdkSuppress(minSdkVersion = 29)
    public void domReachesAndEndsLocalSessionThroughPersonControls() throws Exception {
        ControllerPinManager.enterDomMode();
        assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3_600_000L));
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            PersonActivity person = resumed(PersonActivity.class);
            await(() -> hasTag(person, "person_session:local"));
            onMain(() -> {
                assertTrue(person.findViewById(R.id.nav_people).isSelected());
                assertEquals(context.getString(R.string.role_end_session),
                        ((TextView) person.findViewById(R.id.person_session_action)).getText().toString());
            });
            tap(person, R.id.person_session_action);
            assertFalse(com.subhub.app.commitment.CommitmentManager.isActive(context));
            assertFalse(new AppModeManager(context).isArmed());
            await(() -> hasTag(person, "person_session:local"));
            capture(person, "dom-local-session-ended");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void localProfileSurvivesRecreationAndEndsUntimedProtection() throws Exception {
        ControllerPinManager.enterDomMode();
        com.subhub.app.appmode.ProtectionSessionManager.markMediaProjectionStarted(context);
        new AppModeManager(context).setArmed(true);
        Intent target = new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(target)) {
            scenario.recreate();
            PersonActivity session = resumed(PersonActivity.class);
            await(() -> hasTag(session, "person_session:local"));
            tap(session, R.id.person_session_action);
            assertFalse(new AppModeManager(context).isArmed());
            assertFalse(com.subhub.app.appmode.ProtectionSessionManager.needsMediaProjectionResume(context));
            assertFalse(com.subhub.app.commitment.CommitmentManager.isActive(context));
            await(() -> hasTag(session, "person_session:local"));
            onMain(() -> assertEquals(context.getString(R.string.role_start_session),
                    ((TextView) session.findViewById(R.id.person_session_action)).getText().toString()));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subHasNoEndSessionControlAndCannotInvokeTheLocalAction() throws Exception {
        ControllerPinManager.enterDomMode();
        assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3_600_000L));
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity session = resumed(MainActivity.class);
            await(() -> session.findViewById(R.id.button_protection).getVisibility() == View.GONE);
            onMain(() -> {
                assertFalse(session.findViewById(R.id.button_protection).isShown());
                session.findViewById(R.id.button_protection).performClick();
            });
            assertFalse(LocalSessionControls.end(context, PersonId.LOCAL));
            assertTrue(com.subhub.app.commitment.CommitmentManager.isActive(context));
            assertFalse(ControllerPinManager.isDomModeActive());
            capture(session, "sub-active-session-read-only");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void domCanEndPaidPauseWithoutItsExpiryRearmingTheSession() throws Exception {
        ControllerPinManager.enterDomMode();
        new com.subhub.app.penance.PenanceManager(context).configure(true, 100, 500, 2000, 0);
        com.subhub.app.penance.PaidPauseManager pause = new com.subhub.app.penance.PaidPauseManager(context);
        pause.configure(true, 500, 15);
        assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3_600_000L));
        assertTrue(pause.activate());
        assertTrue(pause.isActive());
        Intent target = new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(target)) {
            PersonActivity session = resumed(PersonActivity.class);
            await(() -> hasTag(session, "person_session:local"));
            onMain(() -> assertTrue(session.findViewById(R.id.person_session_action).isEnabled()));
            tap(session, R.id.person_session_action);
            assertFalse(com.subhub.app.commitment.CommitmentManager.isActive(context));
            assertFalse(context.getSharedPreferences("subhub_penance", 0).contains("paid_pause_active_until"));
            new com.subhub.app.penance.PaidPauseManager(context).finish();
            assertFalse(new AppModeManager(context).isArmed());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remoteSessionIntentCannotOperateOnThisDevice() {
        ControllerPinManager.enterDomMode();
        Map<String, ?> before = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
        Intent target = home().putExtra(MainActivity.EXTRA_LOCAL_SESSION, true)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, "peer:synthetic-alex");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(target)) {
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
        assertEquals(before, new SettingsRepository(context).preferences().getAll());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void peopleSupportsSeveralSubsAndAddsAnotherFromItsPrimaryAction() throws Exception {
        PersonId alex = new PersonId("peer:synthetic-alex"), sam = new PersonId("peer:synthetic-sam");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(sam, alex); }
            public PersonSnapshot load(PersonId id) { return remote(id); }
        });
        AtomicReference<Boolean> asDom = new AtomicReference<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { fail("Pairing must carry the selected role"); }
            public void openPairing(Activity activity, boolean dom) { asDom.set(dom); }
            public void openConnection(Activity activity, PersonId id) { }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            PeopleActivity people = resumed(PeopleActivity.class);
            await(() -> hasTag(people, "person:" + alex.value()) && hasTag(people, "person:" + sam.value()));
            assertTrue(hasTag(people, "person:local"));
            onMain(() -> {
                View root = people.getWindow().getDecorView();
                assertTrue(root.findViewWithTag("person:local").getTop()
                        < root.findViewWithTag("person:" + alex.value()).getTop());
                assertTrue(root.findViewWithTag("person:" + alex.value()).getTop()
                        < root.findViewWithTag("person:" + sam.value()).getTop());
                assertFits(root);
            });
            tap(people, R.id.people_add_sub);
            assertEquals(Boolean.TRUE, asDom.get());
            capture(people, "people-multiple-subs");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subConnectionEntryOffersPairingWithoutExposingSecurityEdits() throws Exception {
        AtomicReference<Boolean> asDom = new AtomicReference<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { fail("Pairing must carry the selected role"); }
            public void openPairing(Activity activity, boolean dom) { asDom.set(dom); }
            public void openConnection(Activity activity, PersonId id) { }
        });
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            GlobalSettingsActivity settings = resumed(GlobalSettingsActivity.class);
            tap(settings, R.id.settings_connections_security);
            com.subhub.app.security.AuthenticatorActivity connection =
                    resumed(com.subhub.app.security.AuthenticatorActivity.class);
            onMain(() -> {
                assertNull(connection.findViewById(R.id.keyholder_pin_change_button));
                assertNull(connection.findViewById(R.id.keyholder_pair_button));
                assertNull(connection.findViewById(R.id.authenticator_pairing_qr));
                assertFalse(ControllerPinManager.isDomModeActive());
                assertHeaderTextClearsControls(connection);
            });
            // This secondary page has no floating navigation to scroll around.
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.keyholder_pair_device))
                    .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click());
            assertEquals(Boolean.FALSE, asDom.get());
            capture(connection, "sub-connection-security");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remoteProfileDispatchesItsOwnContractAndStatsActions() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { return remote(id); }
        });
        List<String> actions = new java.util.concurrent.CopyOnWriteArrayList<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { assertEquals(peer, id); }
            public void openContracts(Activity activity, PersonId id) { assertEquals(peer, id); actions.add("contracts"); }
            public void editSection(Activity activity, PersonId id, String section) { assertEquals(peer, id); actions.add(section); }
            public void openStats(Activity activity, PersonId id) { assertEquals(peer, id); actions.add("stats"); }
        });
        ControllerPinManager.enterDomMode();
        Map<String, ?> before = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> bodyContains(profile, "Synthetic Alex"));
            assertFalse(hasTag(profile, "person_session:local"));
            tapTag(profile, "person_section:censor");
            tapTag(profile, "person_saved_contracts");
            tapTag(profile, "person_tab:stats");
            await(() -> bodyContains(profile, context.getString(R.string.role_view_stats)));
            tapTag(profile, "person_full_stats");
            assertEquals(List.of("censor", "contracts", "stats"), actions);
        }
        assertEquals(before, new SettingsRepository(context).preferences().getAll());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void unknownRemoteStatisticsAreNotShownAsZeroUsage() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) {
                return new PersonSnapshot(id, "Synthetic Alex", "Pixel 8", "Evening contract", "Current state unknown",
                        PersonSnapshot.Connection.OFFLINE, PersonSnapshot.Freshness.UNKNOWN, 0,
                        EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS),
                        Collections.emptyList(), Collections.emptyList(), "EUR", 0, 0, 0, 0, false,
                        new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                                PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN, "revision-1", "revision-1"));
            }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> bodyContains(profile, "Synthetic Alex"));
            tapTag(profile, "person_tab:stats");
            await(() -> bodyContains(profile, context.getString(R.string.role_unknown_data)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_service_time)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_sessions)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_tribute)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_app_time)));
            capture(profile, "remote-statistics-unknown");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remoteEndDispatchesTheDisplayedSessionWithoutEndingLocalService() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        PersonSnapshot snapshot = remoteSession(peer, PersonSnapshot.Freshness.CURRENT, true, true, "session-generation-1");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { assertEquals(peer, id); return snapshot; }
        });
        List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
            public void endSession(Activity activity, PersonId id, String expectedSessionIdentity) {
                assertEquals(peer, id);
                commands.add(expectedSessionIdentity);
            }
        });
        ControllerPinManager.enterDomMode();
        assertTrue(com.subhub.app.commitment.CommitmentManager.start(context, 3_600_000L));
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_session:" + peer.value()));
            onMain(() -> assertFits(profile.getWindow().getDecorView()));
            assertTrue(bodyContains(profile, context.getString(R.string.role_connection_paired)));
            tap(profile, R.id.person_session_action);
            assertEquals(List.of("session-generation-1"), commands);
            assertTrue(com.subhub.app.commitment.CommitmentManager.isActive(context));
            AtomicReference<View> stale = new AtomicReference<>();
            onMain(() -> stale.set(profile.findViewById(R.id.person_session_action)));
            ControllerPinManager.enterSubMode();
            onMain(() -> stale.get().performClick());
            assertEquals(List.of("session-generation-1"), commands);
            assertTrue(com.subhub.app.commitment.CommitmentManager.isActive(context));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void remoteEndRequiresFreshActiveRuntimeCapabilityIdentityAndProvider() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        AtomicReference<PersonSnapshot> snapshot = new AtomicReference<>();
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { return snapshot.get(); }
        });
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
            public void endSession(Activity activity, PersonId id, String expectedSessionIdentity) {
                fail("An ineligible snapshot must not expose End session");
            }
        });
        ControllerPinManager.enterDomMode();
        for (PersonSnapshot value : List.of(
                remoteSession(peer, PersonSnapshot.Freshness.UNKNOWN, true, true, "generation"),
                remoteSession(peer, PersonSnapshot.Freshness.STALE, true, true, "generation"),
                remoteSession(peer, PersonSnapshot.Freshness.CURRENT, false, true, "generation"),
                remoteSession(peer, PersonSnapshot.Freshness.CURRENT, true, false, "generation"),
                remoteSession(peer, PersonSnapshot.Freshness.CURRENT, true, true, ""))) {
            snapshot.set(value);
            try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
                PersonActivity profile = resumed(PersonActivity.class);
                await(() -> bodyContains(profile, "Synthetic Alex"));
                onMain(() -> assertNull(profile.findViewById(R.id.person_session_action)));
            }
        }
        snapshot.set(remoteSession(peer, PersonSnapshot.Freshness.CURRENT, true, true, "generation"));
        People.installConnectionUi(null);
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> bodyContains(profile, "Synthetic Alex"));
            onMain(() -> assertNull(profile.findViewById(R.id.person_session_action)));
        }
    }

    private PersonSnapshot remoteSession(PersonId id, PersonSnapshot.Freshness runtime,
            boolean endCapability, boolean active, String identity) {
        EnumSet<PersonSnapshot.Capability> capabilities = EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT);
        if (endCapability) capabilities.add(PersonSnapshot.Capability.END_SESSION);
        return new PersonSnapshot(id, "Synthetic Alex", "Pixel 8", "Evening contract", active ? "Service active" : "Service inactive",
                PersonSnapshot.Connection.PAIRED, PersonSnapshot.Freshness.CURRENT, 0, capabilities,
                Collections.emptyList(), Collections.emptyList(), "EUR", 0, 0, 0, 0, active,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT,
                        runtime, "1", "1"), identity);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void unknownRemoteAllowanceShowsItsLimitWithoutZeroUsageOrProgress() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) {
                return new PersonSnapshot(id, "Synthetic Alex", "Pixel 8", "Evening contract", "Unknown",
                        PersonSnapshot.Connection.PAIRED, PersonSnapshot.Freshness.CURRENT, 0,
                        EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT), Collections.emptyList(),
                        List.of(new PersonSnapshot.Allowance("com.android.chrome", "Chrome", -1, 35)),
                        "EUR", 0, 0, 0, 0, false,
                        new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                                PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN, "1", "1"));
            }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> bodyContains(profile, context.getString(R.string.role_allowance_unknown, 35)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_allowance_usage, 0, 35)));
            onMain(() -> assertNoUsageProgress(profile.findViewById(R.id.role_page_body)));
            capture(profile, "remote-allowance-unknown");
        }
    }

    private static void assertNoUsageProgress(View view) {
        assertFalse(view instanceof android.widget.ProgressBar);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNoUsageProgress(((ViewGroup) view).getChildAt(i));
    }

    private void assertHeaderTextClearsControls(Activity activity) {
        TextView title = activity.findViewById(R.id.primary_header_title);
        int[] origin = new int[2];
        title.getLocationOnScreen(origin);
        android.text.Layout layout = title.getLayout();
        assertNotNull(layout);
        for (int id : new int[] {R.id.button_back, R.id.button_edit_lock}) {
            Rect control = new Rect();
            View view = activity.findViewById(id);
            if (view == null || !view.getGlobalVisibleRect(control)) continue;
            for (int line = 0; line < layout.getLineCount(); line++) {
                RectF text = new RectF(origin[0] + title.getCompoundPaddingLeft() + layout.getLineLeft(line),
                        origin[1] + title.getCompoundPaddingTop() + layout.getLineTop(line),
                        origin[0] + title.getCompoundPaddingLeft() + layout.getLineMax(line),
                        origin[1] + title.getCompoundPaddingTop() + layout.getLineBottom(line));
                assertFalse("Header text overlaps a control", RectF.intersects(text, new RectF(control)));
            }
        }
    }


    private PersonSnapshot remote(PersonId id) {
        return new PersonSnapshot(id, id.value().contains("sam") ? "Synthetic Sam" : "Synthetic Alex", "Pixel 8", "Evening contract", "Service active",
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

    private void tapTag(Activity activity, String tag) throws Exception {
        AtomicReference<Integer> id = new AtomicReference<>();
        onMain(() -> {
            View target = activity.getWindow().getDecorView().findViewWithTag(tag);
            assertNotNull("Missing control: " + tag, target);
            if (target.getId() == View.NO_ID) target.setId(View.generateViewId());
            id.set(target.getId());
        });
        tap(activity, id.get());
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
        String resource = null;
        String witness = "generated-" + Integer.toHexString(id);
        try {
            resource = activity.getResources().getResourceName(id);
            witness = activity.getResources().getResourceEntryName(id);
        } catch (android.content.res.Resources.NotFoundException generatedId) {
            // Runtime-generated IDs identify Views without a packaged resource entry.
        }
        capture(activity, "tap-" + (++tapSequence) + "-" + witness);
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
        point.put("resource", resource == null ? org.json.JSONObject.NULL : resource);
        point.put("viewId", id);
        AtomicReference<Object> tag = new AtomicReference<>();
        onMain(() -> tag.set(activity.findViewById(id).getTag()));
        if (tag.get() instanceof String) point.put("tag", tag.get());
        point.put("bounds", new org.json.JSONArray(new int[] {rect.left, rect.top, rect.right, rect.bottom}));
        point.put("x", rect.exactCenterX()); point.put("y", rect.exactCenterY());
        point.put("source", "SOURCE_TOUCHSCREEN");
        point.put("test", testName.getMethodName());
        try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(),
                "ui-v3/" + run + "/" + witnessPrefix() + "-tap-" + tapSequence + "-point.json"))) {
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
        name = witnessPrefix() + "-" + name;
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

    private String witnessPrefix() { return Integer.toHexString(testName.getMethodName().hashCode()); }

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
