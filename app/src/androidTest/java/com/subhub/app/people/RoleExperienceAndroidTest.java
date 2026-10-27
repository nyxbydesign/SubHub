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
    @org.junit.Rule public final com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new com.subhub.app.security.KeyholderPresentationAndroidTest.IntegrationFixture();
    @org.junit.Rule public final org.junit.rules.TestName testName = new org.junit.rules.TestName();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Map<String, ?>> original = new LinkedHashMap<>();
    private String originalServices, originalAccessibility;
    private int tapSequence;
    private boolean keepAccessibilityServices;
    private com.subhub.app.activity.ActivityRepository originalActivity;

    @Before public void prepare() {
        originalServices = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        originalAccessibility = Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        automation();
        for (String name : List.of(SettingsRepository.PREFERENCES_NAME, "subhub_onboarding", "subhub_home",
                "subhub_service_duration_ui", "subhub_app_timer_usage", "subhub_penance",
                "betablocker_stats", "betablocker_achievements", FavoriteSubStore.PREFERENCES_NAME)) {
            original.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        }
        originalActivity = com.subhub.app.activity.ActivitySources.repository();
        com.subhub.app.activity.ActivitySources.install(new com.subhub.app.activity.ActivityRepository() {
            public Page load(String before, int limit) { return new Page(List.of(), null); }
            public Page locate(String key, int limit) { return new Page(List.of(), null); }
            public List<com.subhub.app.activity.ActivityEntry> recent(PersonId person, int limit) { return List.of(); }
            public int unreadCount() { return 0; }
            public void markRead(List<String> keys) { }
        });
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        People.installRemoteRepository(null);
        People.installRemoteAuthority(() -> false);
        People.installConnectionUi(null);
        assertTrue(new SettingsRepository(context).preferences().edit()
                .putString(com.subhub.app.security.DeviceRolePreferences.KEY_MODE,
                        com.subhub.app.security.DeviceRolePreferences.Mode.BOTH.name()).commit());
        OnboardingState.complete(context);
        Set<String> apps = new LinkedHashSet<>(List.of("com.android.chrome", "com.android.vending"));
        for (String app : apps) {
            try { context.getPackageManager().getApplicationInfo(app, 0); }
            catch (Exception missing) { throw new AssertionError("Capture fixture app is missing: " + app, missing); }
        }
        AppModeManager assignments = new AppModeManager(context);
        assignments.setAllAppsEnabled(false);
        assignments.saveIncludedPackages(apps);
        AppTimerManager timers = new AppTimerManager(context);
        timers.saveSettings(true, 30, false, 120);
        timers.saveAllowances(apps, Map.of("com.android.chrome", 20, "com.android.vending", 10));
        assertEquals(apps, new AppModeManager(context).getIncludedPackages());
        assertEquals(20, timers.allowanceMinutes("com.android.chrome"));
        assertEquals(10, timers.allowanceMinutes("com.android.vending"));
    }

    @After public void restore() throws Exception {
        keepAccessibilityServices = false;
        automation();
        com.subhub.app.commitment.CommitmentManager.emergencyRelease(context);
        if (originalActivity != null) com.subhub.app.activity.ActivitySources.install(originalActivity);
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
        assertTrue(context.getSharedPreferences("betablocker_achievements", 0).edit()
                .putStringSet("unlocked", java.util.Set.of("first_session")).commit());
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            await(() -> visibleChildCount(MainActivity.class, R.id.home_sub_summary) >= 2);
            MainActivity home = resumed(MainActivity.class);
            capture(home, "sub-overview");
            await(() -> previewReady(home));
            onMain(() -> assertTrue(contains(home.getWindow().getDecorView().findViewWithTag("achievement_preview"),
                    context.getString(R.string.achievements_title))));
            ProfileStatsActivityAndroidTest.captureNativeProfile(home, "sub-achievements", "achievement_preview");
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
            await(() -> hasTag(contract, "person_contract_scope"));
            capture(contract, "sub-contract");
            onMain(() -> {
                assertFalse(hasInput(contract.findViewById(R.id.role_page_body)));
                assertTrue(contract.findViewById(R.id.nav_contract).isSelected());
                assertFits(contract.getWindow().getDecorView());
            });
            tap(contract, R.id.nav_stats);
            StatsActivity stats = resumed(StatsActivity.class);
            await(() -> previewReady(stats));
            ProfileStatsActivityAndroidTest.captureNativeProfile(stats, "stats-achievements", "achievement_preview");
            capture(stats, "sub-stats");
            onMain(() -> assertTrue(stats.findViewById(R.id.nav_stats).isSelected()));
            tap(stats, R.id.nav_settings);
            GlobalSettingsActivity settings = resumed(GlobalSettingsActivity.class);
            capture(settings, "sub-settings");
            onMain(() -> {
                assertNull(settings.findViewById(R.id.apps_card));
                assertNull(settings.findViewById(R.id.settings_connection));
                assertTrue(settings.findViewById(R.id.settings_connections_security).isShown());
                assertTrue(settings.findViewById(R.id.hardcore_card).isShown());
                assertTrue(settings.findViewById(R.id.privacy_discreet_toggle).isShown());
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
                assertVisibleTabs(home, R.id.nav_home, R.id.nav_people, R.id.nav_activity, R.id.nav_settings);
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
            await(() -> hasTag(person, "person_session:local"));
            onMain(() -> {
                assertNotNull(person.findViewById(R.id.role_page_body).findViewWithTag("person_tab:contracts"));
                assertNotNull(person.findViewById(R.id.role_page_body).findViewWithTag("person_tab:stats"));
                assertNotNull(person.findViewById(R.id.role_page_body).findViewWithTag("person_tab:connection"));
                assertNotNull(person.findViewById(R.id.commitment_timer_1h));
                assertTrue(person.findViewById(R.id.commitment_timer_1h).isEnabled());
                assertNotNull(person.findViewById(R.id.role_page_body).findViewWithTag("person_duration:local"));
            });
            tap(person, R.id.commitment_timer_1h);
            assertEquals(3_600_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
            tapTag(person, "person_tab:contracts");
            await(() -> hasTag(person, "person_apps"));
            assertChildHasNoSession(person);
            capture(person, "dom-person-contracts");
            tapTag(person, "person_apps");
            LocalAppsActivity apps = resumed(LocalAppsActivity.class);
            await(() -> appPickerLoaded(apps));
            onMain(() -> {
                assertEquals(PersonId.LOCAL, PersonRoutes.requireId(apps.getIntent()));
                assertEquals(context.getString(R.string.role_this_device),
                        ((TextView) apps.findViewById(R.id.person_editor_target)).getText().toString());
                assertFits(apps.getWindow().getDecorView());
            });
            assertEquals(Set.of("com.android.chrome", "com.android.vending"), new AppModeManager(context).getIncludedPackages());
            tap(apps, R.id.button_back);
            resumed(PersonActivity.class);
            await(() -> hasTag(person, "person_apps"));
            assertChildHasNoSession(person);
            tap(person, R.id.button_back);
            await(() -> hasTag(person, "person_session:local"));
            tapTag(person, "person_tab:connection");
            await(() -> bodyContains(person, context.getString(R.string.role_device)));
            assertChildHasNoSession(person);
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(() -> hasTag(person, "person_session:local"));
            AtomicReference<View> stale = new AtomicReference<>();
            onMain(() -> stale.set(person.findViewById(R.id.commitment_timer_24h)));
            tap(person, R.id.nav_home);
            MainActivity returnedHome = resumed(MainActivity.class);
            tap(returnedHome, R.id.button_edit_lock);
            assertFalse(ControllerPinManager.isDomModeActive());
            onMain(() -> {
                // Even an already-delivered stale listener must no longer persist a choice.
                stale.get().performClick();
            });
            assertEquals(3_600_000L, context.getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", 0));
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subCannotEditByReopeningAnOldPersonPage() {
        ControllerPinManager.enterSubMode();
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
            tapTag(person, "person_tab:contracts");
            await(() -> hasTag(person, "person_section:censor"));
            assertChildHasNoSession(person);
            capture(person, "remote-person-offline-fixture");
            onMain(() -> {
                assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration:" + remoteId.value()));
                assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration"));
                View apps = person.getWindow().getDecorView().findViewWithTag("person_apps");
                assertNotNull(apps);
                assertFalse(apps.isClickable());
                PersonRoutes.editSection(person, remoteId, "censor");
                PersonRoutes.editSection(person, remoteId, "apps");
                PersonRoutes.editSection(person, remoteId, "duration");
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
            await(() -> hasTag(person, "person_session:local"));
            tapTag(person, "person_tab:contracts");
            await(() -> hasTag(person, "person_contract_scope"));
            assertChildHasNoSession(person);
            onMain(() -> {
                assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration:local"));
                assertNull(person.getWindow().getDecorView().findViewWithTag("person_duration"));
                View apps = person.getWindow().getDecorView().findViewWithTag("person_apps");
                assertNotNull(apps);
                assertFalse(apps.isClickable());
            });
        }
        assertEditorRejected(LocalAppsActivity.class, PersonId.LOCAL.value());
        assertEditorRejected(ServiceDurationActivity.class, PersonId.LOCAL.value());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void durationRecreationUsesStoredConfigurationInBothDirections() {
        ControllerPinManager.enterDomMode();
        for (boolean savedWidget : new boolean[] {false, true}) {
            try (ActivityScenario<ServiceDurationActivity> scenario = ActivityScenario.launch(new Intent(context, ServiceDurationActivity.class)
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
                ServiceDurationActivity person = resumed(ServiceDurationActivity.class);
                await(() -> hasTag(person, "person_duration:local"));
                onMain(() -> ((android.widget.CompoundButton) person.findViewById(R.id.service_duration_hide)).setChecked(savedWidget));
                assertTrue(context.getSharedPreferences("subhub_service_duration_ui", 0).edit()
                        .putBoolean("hidden", !savedWidget).commit());
                scenario.recreate();
                ServiceDurationActivity recreated = resumed(ServiceDurationActivity.class);
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
            await(() -> hasTag(activity, "person_contract_scope"));
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
    public void profileStartsSelectedLocalSessionWithoutAnotherMenu() throws Exception {
        keepAccessibilityServices = true;
        automation();
        ControllerPinManager.enterDomMode();
        assertFalse(LocalSessionControls.isActive(context));
        new com.subhub.app.settings.FeatureModuleManager(context).setCensorEnabled(false);
        assertTrue(context.getSharedPreferences("subhub_service_duration_ui", 0).edit()
                .putLong("duration", 3_600_000L).commit());
        String service = context.getPackageName() + "/.service.ScreenshotAccessibilityService";
        restoreSecure("enabled_accessibility_services", originalServices == null || originalServices.isEmpty()
                ? service : originalServices + ":" + service);
        restoreSecure("accessibility_enabled", "1");
        long bindDeadline = SystemClock.uptimeMillis() + 20_000L;
        while (!new AppModeManager(context).isAccessibilityEnabled() && SystemClock.uptimeMillis() < bindDeadline)
            SystemClock.sleep(100);
        assertTrue("Accessibility service did not bind", new AppModeManager(context).isAccessibilityEnabled());
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_session:local"));
            tap(profile, R.id.person_session_action);
            await(() -> com.subhub.app.commitment.CommitmentManager.isActive(context));
            assertEquals(3_600_000L, com.subhub.app.commitment.CommitmentManager.originalDurationMillis(context));
            assertTrue(new AppModeManager(context).isArmed());
            assertTrue(ControllerPinManager.isDomModeActive());
            LocalSessionControls.end(context, PersonId.LOCAL);
        }
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
    public void keyholderDestinationUsesTheAssignedRelationshipWithoutPinControls() throws Exception {
        AtomicReference<PersonId> target = new AtomicReference<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { fail("A paired Sub must open its relationship"); }
            public void openConnection(Activity activity, PersonId id) { target.set(id); }
        });
        People.installKeyholder(com.subhub.app.security.KeyholderPresentationAndroidTest.provider(
                com.subhub.app.security.KeyholderProvider.State.READY, "Synthetic Dom"));
        People.installRemoteAuthority(() -> true);
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<GlobalSettingsActivity> scenario = ActivityScenario.launch(GlobalSettingsActivity.class)) {
            GlobalSettingsActivity settings = resumed(GlobalSettingsActivity.class);
            onMain(() -> {
                assertNull(settings.findViewById(R.id.settings_connection));
                assertEquals(context.getString(R.string.role_connection),
                        ((TextView) settings.findViewById(R.id.settings_connections_security)).getText().toString());
            });
            tap(settings, R.id.settings_connections_security);
            assertEquals("Paired Sub opens its connection directly", PersonId.LOCAL, target.get());
            assertFalse(ControllerPinManager.isDomModeActive());
            onMain(() -> settings.startActivity(new Intent(settings,
                    com.subhub.app.security.AuthenticatorActivity.class)));
            com.subhub.app.security.AuthenticatorActivity keyholder = resumed(com.subhub.app.security.AuthenticatorActivity.class);
            onMain(() -> {
                assertNull(keyholder.findViewById(R.id.keyholder_pin_change_button));
                assertNull(keyholder.findViewById(R.id.keyholder_remove_pin));
                assertNull(keyholder.findViewById(R.id.keyholder_pair_device));
                assertNull(keyholder.findViewById(R.id.authenticator_pairing_qr));
                assertEquals("Synthetic Dom", ((TextView) keyholder.findViewById(R.id.keyholder_assigned_dom)).getText().toString());
                assertEquals(context.getString(R.string.keyholder_home_title),
                        ((TextView) keyholder.findViewById(R.id.primary_header_title)).getText().toString());
                assertFalse(ControllerPinManager.isDomModeActive());
                assertHeaderTextClearsControls(keyholder);
            });
            capture(keyholder, "sub-keyholder");
            tap(keyholder, R.id.settings_connection);
            assertEquals(PersonId.LOCAL, target.get());
            assertFalse(ControllerPinManager.isDomModeActive());
            onMain(keyholder::finish);
            resumed(GlobalSettingsActivity.class);
            People.installKeyholder(com.subhub.app.security.KeyholderPresentationAndroidTest.provider(
                    com.subhub.app.security.KeyholderProvider.State.NONE, ""));
            People.installRemoteAuthority(() -> false);
            ControllerPinManager.enterDomMode();
            scenario.recreate();
            GlobalSettingsActivity domSettings = resumed(GlobalSettingsActivity.class);
            onMain(() -> {
                assertNull(domSettings.findViewById(R.id.settings_connection));
                assertNull(domSettings.findViewById(R.id.apps_card));
            });
            tap(domSettings, R.id.settings_connections_security);
            com.subhub.app.security.AuthenticatorActivity local = resumed(com.subhub.app.security.AuthenticatorActivity.class);
            onMain(() -> {
                assertNotNull(local.findViewById(R.id.keyholder_pin_change_button));
                assertNotNull(local.findViewById(R.id.keyholder_pair_device));
                assertNull(local.findViewById(R.id.authenticator_pairing_qr));
                assertHeaderTextClearsControls(local);
            });
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
            tapTag(profile, "person_tab:contracts");
            await(() -> hasTag(profile, "person_section:censor"));
            assertChildHasNoSession(profile);
            tapTag(profile, "person_apps");
            tapTag(profile, "person_section:censor");
            onMain(() -> assertNull(profile.findViewById(R.id.role_page_body).findViewWithTag("person_saved_contracts")));
            tap(profile, R.id.button_back);
            await(() -> hasTag(profile, "person_tab:stats"));
            tapTag(profile, "person_tab:stats");
            await(() -> actions.contains("stats"));
            assertTrue(hasTag(profile, "person_tab:stats"));
            assertFalse(hasTag(profile, "person_full_stats"));
            assertEquals(List.of("apps", "censor", "stats"), actions);
        }
        assertEquals(before, new SettingsRepository(context).preferences().getAll());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void unknownRemoteStatisticsOpenDirectlyWithoutInventingLocalZeroUsage() throws Exception {
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
        AtomicReference<PersonId> opened = new AtomicReference<>();
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
            public void openStats(Activity activity, PersonId id) { opened.set(id); }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_tab:stats"));
            tapTag(profile, "person_tab:stats");
            assertEquals(peer, opened.get());
            assertFalse(hasTag(profile, "person_full_stats"));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_service_time)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_sessions)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_stat_tribute)));
            assertFalse(bodyContains(profile, context.getString(R.string.role_app_time)));
            assertTrue(hasTag(profile, "person_tab:contracts"));
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

    @Test @SdkSuppress(minSdkVersion = 29)
    public void openRemoteProfileRemovesEndWhenItsRuntimeBecomesStale() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        AtomicReference<PersonSnapshot> current = new AtomicReference<>(remoteSession(peer,
                PersonSnapshot.Freshness.CURRENT, true, true, "generation"));
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { return current.get(); }
        });
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_session:" + peer.value()));
            current.set(remoteSession(peer, PersonSnapshot.Freshness.STALE, true, false, "generation"));
            await(() -> bodyContains(profile, context.getString(R.string.role_stale_data)));
            onMain(() -> assertNull(profile.findViewById(R.id.person_session_action)));
            assertTrue(bodyContains(profile, "Synthetic Alex"));
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
    public void unknownRemoteAllowanceKeepsItsLimitWithoutZeroUsageOrProgress() throws Exception {
        PersonId peer = new PersonId("peer:synthetic-alex");
        PersonSnapshot snapshot = new PersonSnapshot(peer, "Synthetic Alex", "Pixel 8", "Evening contract", "Unknown",
                PersonSnapshot.Connection.PAIRED, PersonSnapshot.Freshness.CURRENT, 0,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS), Collections.emptyList(),
                List.of(new PersonSnapshot.Allowance("com.android.chrome", "Chrome", -1, 35)),
                "EUR", 0, 0, 0, 0, false,
                new PersonSnapshot.SyncState(PersonSnapshot.Freshness.CURRENT,
                        PersonSnapshot.Freshness.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN, "1", "1"));
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(peer); }
            public PersonSnapshot load(PersonId id) { return snapshot; }
        });
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, peer.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_tab:contracts"));
            tapTag(profile, "person_tab:contracts");
            await(() -> hasTag(profile, "person_contract_scope"));
            assertFalse(bodyContains(profile, context.getString(R.string.role_app_time)));
            onMain(() -> {
                LinearLayout fixture = PersonUi.column(profile);
                PersonUi.allowances(fixture, snapshot);
                assertTrue(containsExact(fixture, context.getString(R.string.role_allowance_unknown, 35)));
                assertFalse(containsExact(fixture, context.getString(R.string.role_allowance_usage, 0, 35)));
                assertNoUsageProgress(fixture);
            });
            assertChildHasNoSession(profile);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void favoriteSelectionSurvivesRecreationAndOpensItsOwnProfile() throws Exception {
        PersonId alex = new PersonId("peer:synthetic-alex"), sam = new PersonId("peer:synthetic-sam");
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return List.of(alex, sam); }
            public PersonSnapshot load(PersonId id) { return remote(id); }
        });
        People.installConnectionUi(new ConnectionUi() {
            public void openPairing(Activity activity) { }
            public void openConnection(Activity activity, PersonId id) { }
            public void editSection(Activity activity, PersonId id, String section) { fail("Home must not dispatch hidden quick editors"); }
        });
        Map<String, ?> before = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(home())) {
            MainActivity home = resumed(MainActivity.class);
            await(() -> hasTag(home, "home_choose_favorite"));
            tap(home, R.id.nav_people);
            PeopleActivity people = resumed(PeopleActivity.class);
            await(() -> hasTag(people, "person:" + sam.value()));
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withTagValue(
                    org.hamcrest.Matchers.is("person:" + sam.value())))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), androidx.test.espresso.action.ViewActions.longClick());
            await(() -> FavoriteSubStore.identity(sam).equals(new FavoriteSubStore(context).identity()));
            tap(people, R.id.nav_home);
            MainActivity selected = resumed(MainActivity.class);
            await(() -> hasTag(selected, "home_favorite:" + sam.value()));
            scenario.recreate();
            MainActivity recreated = resumed(MainActivity.class);
            await(() -> hasTag(recreated, "home_favorite_identity:" + sam.value()));
            assertFalse(hasTag(recreated, "home_person_edit:" + sam.value() + ":censor"));
            assertFalse(hasTag(recreated, "home_person_choice:" + alex.value()));
            assertFalse(hasTag(recreated, "home_recent_person:" + alex.value()));
            tapTag(recreated, "home_favorite_identity:" + sam.value());
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_target:" + sam.value()));
            assertEquals(sam, PersonRoutes.requireId(profile.getIntent()));
            assertEquals(before, new SettingsRepository(context).preferences().getAll());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subContractExpandsOneReadOnlySectionAndRestoresIt() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> scenario = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity contract = resumed(ContractActivity.class);
            await(() -> hasTag(contract, "contract_section:censor"));
            onMain(() -> assertEquals(View.GONE, contract.findViewById(R.id.role_page_body)
                    .findViewWithTag("contract_details:censor").getVisibility()));
            tapTag(contract, "contract_section:censor");
            onMain(() -> assertTrue(contract.findViewById(R.id.role_page_body)
                    .findViewWithTag("contract_details:censor").isShown()));
            tapTag(contract, "contract_section:limits");
            onMain(() -> {
                assertEquals(View.GONE, contract.findViewById(R.id.role_page_body)
                        .findViewWithTag("contract_details:censor").getVisibility());
                assertTrue(contract.findViewById(R.id.role_page_body)
                        .findViewWithTag("contract_details:limits").isShown());
            });
            scenario.recreate();
            ContractActivity recreated = resumed(ContractActivity.class);
            await(() -> hasTag(recreated, "contract_section:limits"));
            onMain(() -> assertTrue(recreated.findViewById(R.id.role_page_body)
                    .findViewWithTag("contract_details:limits").isShown()));
            assertFalse(ControllerPinManager.isDomModeActive());
            capture(recreated, "sub-contract-expanded-limits");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void contractScopePrecedesFeaturesAndTargetIdentityDoesNotDependOnGeneration() throws Exception {
        PersonId first = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_first-generation");
        PersonId renamedGeneration = new PersonId("remote_" + String.join("", Collections.nCopies(64, "a")) + "_next-generation");
        PersonId second = new PersonId("remote_" + String.join("", Collections.nCopies(64, "b")) + "_first-generation");
        String firstLabel = ProfileViews.targetDiscriminator(context, first, "Pixel 8");
        assertEquals(firstLabel, ProfileViews.targetDiscriminator(context, renamedGeneration, "Pixel 8"));
        assertNotEquals(firstLabel, ProfileViews.targetDiscriminator(context, second, "Pixel 8"));
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> page = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            PersonActivity profile = resumed(PersonActivity.class);
            await(() -> hasTag(profile, "person_tab:contracts"));
            tapTag(profile, "person_tab:contracts");
            await(() -> hasTag(profile, "person_contract_scope"));
            onMain(() -> {
                ViewGroup body = profile.findViewById(R.id.role_page_body);
                View scope = body.findViewWithTag("person_contract_scope");
                View features = body.findViewWithTag("person_contract_features");
                assertNotNull(features);
                assertTrue(body.indexOfChild(scope) < body.indexOfChild(features));
                assertNotNull(scope.findViewWithTag("person_apps"));
                assertNull(scope.findViewWithTag("person_duration"));
                assertTrue(contains(scope, "Chrome"));
                assertTrue(contains(scope, context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getInstalledApplications(0).stream()
                                .filter(info -> "com.android.vending".equals(info.packageName)).findFirst().get()).toString()));
                assertFalse(contains(body, context.getString(R.string.role_active_contract)));
                assertFalse(contains(body, context.getString(R.string.role_app_time)));
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void profileChildrenRestoreTheirSectionAndBackReturnsToStatus() throws Exception {
        ControllerPinManager.enterDomMode();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(new Intent(context, PersonActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            for (String section : List.of("contracts", "connection")) {
                PersonActivity profile = resumed(PersonActivity.class);
                await(() -> hasTag(profile, "person_session:local"));
                tapTag(profile, "person_tab:" + section);
                await(() -> childReady(profile, section));
                assertChildHasNoSession(profile);
                scenario.recreate();
                PersonActivity restored = resumed(PersonActivity.class);
                await(() -> childReady(restored, section));
                assertChildHasNoSession(restored);
                tap(restored, R.id.button_back);
                await(() -> hasTag(restored, "person_session:local"));
                assertTrue(hasTag(restored, "person_tab:" + section));
            }
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void appsRecreationKeepsTheSavedScopeAndStaleChoicesCannotWrite() throws Exception {
        ControllerPinManager.enterDomMode();
        AppModeManager assignments = new AppModeManager(context);
        Set<String> selected = Set.of("com.android.chrome", "com.android.vending");
        try (ActivityScenario<LocalAppsActivity> scenario = ActivityScenario.launch(new Intent(context, LocalAppsActivity.class)
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()))) {
            LocalAppsActivity apps = resumed(LocalAppsActivity.class);
            await(() -> appPickerLoaded(apps));
            tap(apps, R.id.all_apps);
            assertTrue(assignments.isAllApps());
            assertEquals(selected, assignments.getSelectedPackages());
            scenario.recreate();
            LocalAppsActivity recreated = resumed(LocalAppsActivity.class);
            await(() -> appPickerLoaded(recreated));
            onMain(() -> assertTrue(((android.widget.CompoundButton) recreated.findViewById(R.id.all_apps)).isChecked()));
            tap(recreated, R.id.all_apps);
            assertFalse(assignments.isAllApps());
            assertEquals(selected, assignments.getIncludedPackages());
            assertEquals(20, new AppTimerManager(context).allowanceMinutes("com.android.chrome"));
            assertEquals(10, new AppTimerManager(context).allowanceMinutes("com.android.vending"));
            Map<String, ?> beforeStale = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
            AtomicReference<View> stale = new AtomicReference<>();
            onMain(() -> stale.set(recreated.findViewById(R.id.all_apps)));
            ControllerPinManager.enterSubMode();
            onMain(() -> stale.get().performClick());
            assertFalse(assignments.isAllApps());
            assertEquals(selected, assignments.getIncludedPackages());
            assertEquals(beforeStale, new SettingsRepository(context).preferences().getAll());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void localContractEditorsRejectMissingRemoteAndSubTargets() {
        Map<String, ?> settings = new LinkedHashMap<>(new SettingsRepository(context).preferences().getAll());
        Map<String, ?> duration = new LinkedHashMap<>(context.getSharedPreferences("subhub_service_duration_ui", 0).getAll());
        ControllerPinManager.enterDomMode();
        for (Class<? extends Activity> type : List.of(LocalAppsActivity.class, ServiceDurationActivity.class)) {
            assertEditorRejected(type, null);
            assertEditorRejected(type, "peer:synthetic-alex");
        }
        ControllerPinManager.enterSubMode();
        assertEditorRejected(LocalAppsActivity.class, PersonId.LOCAL.value());
        assertEditorRejected(ServiceDurationActivity.class, PersonId.LOCAL.value());
        assertEquals(settings, new SettingsRepository(context).preferences().getAll());
        assertEquals(duration, context.getSharedPreferences("subhub_service_duration_ui", 0).getAll());
    }

    private void assertEditorRejected(Class<? extends Activity> type, String person) {
        Intent intent = new Intent(context, type);
        if (person != null) intent.putExtra(PersonRoutes.EXTRA_PERSON_ID, person);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent)) {
            await(() -> scenario.getState() == Lifecycle.State.DESTROYED);
            assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
        }
    }

    private boolean appPickerLoaded(Activity activity) {
        AtomicReference<Boolean> loaded = new AtomicReference<>(false);
        onMain(() -> {
            android.widget.ListView list = activity.findViewById(R.id.app_list);
            loaded.set(list != null && list.getAdapter() != null && list.getAdapter().getCount() > 0);
        });
        return loaded.get();
    }

    private boolean previewReady(Activity activity) {
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        onMain(() -> {
            View view = activity.getWindow().getDecorView().findViewWithTag("achievement_preview");
            if (!(view instanceof ViewGroup)) return;
            ViewGroup preview = (ViewGroup) view;
            if (preview.getChildCount() < 3 || !(preview.getChildAt(2) instanceof ViewGroup)) return;
            ready.set(((ViewGroup) preview.getChildAt(2)).getChildCount() == 3);
        });
        return ready.get();
    }
    private boolean childReady(PersonActivity activity, String section) {
        if ("contracts".equals(section)) return hasTag(activity, "person_apps");
        return bodyContains(activity, context.getString(R.string.role_device));
    }

    private void assertChildHasNoSession(PersonActivity activity) {
        onMain(() -> {
            View body = activity.findViewById(R.id.role_page_body);
            assertNull(body.findViewById(R.id.person_session_action));
            assertFalse(containsExact(body, context.getString(R.string.role_session)));
            for (String destination : List.of("contracts", "stats", "connection"))
                assertNull(body.findViewWithTag("person_tab:" + destination));
            assertTrue(activity.findViewById(R.id.nav_people).isSelected());
        });
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
        return instrumentation.getUiAutomation(keepAccessibilityServices
                ? UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES : UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
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
                boolean settled = activity.hasWindowFocus() && !root.isLayoutRequested()
                        && !containsVisibleText(root, activity.getString(R.string.role_loading));
                View snackbar = root.findViewById(com.google.android.material.R.id.snackbar_text);
                if (snackbar != null && snackbar.isShown()) settled = false;
                ViewGroup nav = activity.findViewById(R.id.bottom_navigation);
                if (nav != null) for (int i = 0; i < nav.getChildCount(); i++) {
                    View child = nav.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE && (child.getWidth() == 0 || child.getHeight() == 0)) settled = false;
                }
                ready.set(settled);
            });
            return ready.get();
        });
        Bitmap pixels = settledCompositorPixels(activity, name);
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

    private static boolean containsVisibleText(View view, String text) {
        if (!view.isShown()) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsVisibleText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    /** Frame commits precede compositor transitions; compare actual pixels before accepting a render. */
    private Bitmap settledCompositorPixels(Activity activity, String captureName) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        long stableSince = 0;
        Bitmap previous = null;
        try {
            do {
                CountDownLatch committed = new CountDownLatch(1);
                onMain(() -> {
                    View root = activity.getWindow().getDecorView();
                    root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    root.invalidate();
                });
                assertTrue("No native render frame committed", committed.await(2, TimeUnit.SECONDS));
                Bitmap current = automation().takeScreenshot();
                assertNotNull(current);
                long now = SystemClock.uptimeMillis();
                boolean same = previous != null && previous.sameAs(current);
                if (!same) stableSince = now;
                if (previous != null) previous.recycle();
                previous = current;
                AtomicReference<Boolean> stillReady = new AtomicReference<>(false);
                onMain(() -> {
                    View root = activity.getWindow().getDecorView();
                    View snackbar = root.findViewById(com.google.android.material.R.id.snackbar_text);
                    stillReady.set(activity.hasWindowFocus() && !root.isLayoutRequested()
                            && !containsVisibleText(root, activity.getString(R.string.role_loading))
                            && (snackbar == null || !snackbar.isShown()));
                });
                if (!stillReady.get()) stableSince = now;
                if (same && stillReady.get() && now - stableSince >= 200) {
                    Bitmap result = previous; previous = null; return result;
                }
            } while (SystemClock.uptimeMillis() < deadline);
            throw new AssertionError("Native compositor did not settle");
        } catch (Exception | AssertionError failure) {
            try {
                if (previous == null) previous = automation().takeScreenshot();
                if (previous != null) {
                    String run = InstrumentationRegistry.getArguments().getString("evidence_run", "candidate-1");
                    if (!run.matches("[A-Za-z0-9_-]+")) throw new AssertionError("Invalid evidence namespace");
                    File folder = new File(context.getFilesDir(), "ui-v3/" + run);
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    File retained = new File(folder, captureName + "-compositor-failure-"
                            + SystemClock.uptimeMillis() + ".png");
                    try (FileOutputStream output = new FileOutputStream(retained)) {
                        assertTrue(previous.compress(Bitmap.CompressFormat.PNG, 100, output));
                    }
                }
            } catch (Exception | AssertionError exportFailure) { failure.addSuppressed(exportFailure); }
            throw failure;
        } finally { if (previous != null) previous.recycle(); }
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

    private static boolean containsExact(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (containsExact(((ViewGroup) view).getChildAt(index), value)) return true;
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
