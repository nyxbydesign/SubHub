package com.subhub.app.people;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
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
    @Rule public final OwnedUiIntegrationFixture integration = new OwnedUiIntegrationFixture(false);
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private DummyPreviewEndpointFixture endpoint;
    private InternalSubUiFixture internalFixture;
    private com.subhub.app.stats.DummyPreviewHistoryWitness history;
    private int previewTransportBaseline = -1;
    private Object journalLock, originalJournalState;
    private Map<String, Object> journalStates;
    private String journalIdentity;
    private boolean journalWasPresent, journalIsolated;
    private boolean originalDom;
    private static final String[] STORES = {"subhub_dummy_preview", "subhub_profile_names",
            SettingsRepository.PREFERENCES_NAME, SubFeaturePolicy.STORE, "subhub_relationship_presentation",
            "subhub_service_duration_ui", "profile_configuration_identity", "subhub_extra_time_v1",
            "subhub_controller_auth", "subhub_peer_transport", "subhub_foreground_presence",
            com.subhub.app.penance.PenanceManager.PREFS_NAME};

    @Before public void prepare() throws Exception {
        originalDom = ControllerPinManager.isDomModeActive();
        for (String store : preferenceStores()) saved.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        isolateJournalCache();
        assertFalse("Use an idle, owned UI guest", LocalSessionControls.isActive(context));
        assertTrue(context.getSharedPreferences("subhub_dummy_preview", 0).edit().clear().commit());
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertTrue("Fixture role transition must respect the controller guard", DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        internalFixture = new InternalSubUiFixture(context);
        endpoint = new DummyPreviewEndpointFixture(context);
        history = new com.subhub.app.stats.DummyPreviewHistoryWitness(context);
        instrumentation.waitForIdleSync();
    }
    @After public void restore() throws Exception {
        OwnedFixtureCleanup cleanup=new OwnedFixtureCleanup();
        cleanup.attempt(integration::drainReaders);
        closeOwnedFixtures(cleanup,endpoint,internalFixture);
        for(String store:preferenceStores())cleanup.attempt(()->SharedPreferenceTestRestore.restore(
                context.getSharedPreferences(store,0),saved.containsKey(store)?saved.get(store):Collections.emptyMap()));
        cleanup.attempt(this::restoreJournalCache);
        cleanup.attempt(()->{if(originalDom)ControllerPinManager.enterDomMode();else ControllerPinManager.enterSubMode();});
        cleanup.finish();
    }
    private static void closeOwnedFixtures(OwnedFixtureCleanup cleanup,AutoCloseable endpoint,AutoCloseable internal) {
        cleanup.attempt(()->{if(endpoint!=null)endpoint.close();});
        cleanup.attempt(()->{if(internal!=null)internal.close();});
    }
    @SuppressWarnings("unchecked") private void isolateJournalCache() throws Exception {
        Class<?> owner = com.subhub.app.relationships.RelationshipPanel.class;
        java.lang.reflect.Field lock = owner.getDeclaredField("JOURNAL_LOCK"); lock.setAccessible(true);
        java.lang.reflect.Field states = owner.getDeclaredField("JOURNAL_STATES"); states.setAccessible(true);
        journalLock = lock.get(null); journalStates = (Map<String, Object>) states.get(null);
        journalIdentity = context.getApplicationInfo().dataDir + "/subhub_relationship_presentation";
        synchronized (journalLock) {
            journalWasPresent = journalStates.containsKey(journalIdentity);
            originalJournalState = journalStates.remove(journalIdentity);
            journalIsolated = true;
        }
    }
    private void restoreJournalCache() {
        if (!journalIsolated) return;
        synchronized (journalLock) {
            // Reinstate the retained object, never mutate its fields or clear another journal's cache.
            if (journalWasPresent) journalStates.put(journalIdentity, originalJournalState);
            else journalStates.remove(journalIdentity);
            journalIsolated = false;
            assertEquals("Restore the journal cache's original presence", journalWasPresent, journalStates.containsKey(journalIdentity));
            assertSame("Restore the exact original journal state", originalJournalState, journalStates.get(journalIdentity));
        }
    }

    @Test public void directConnectionFiveSecondHoldAddsAndRemovesWithoutOpeningPreview() throws Exception {
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            Instrumentation.ActivityMonitor previews = instrumentation.addMonitor(PersonActivity.class.getName(), null, false);
            try {
                for (boolean expected : new boolean[]{true, false}) {
                    PairingActivity pairing = openConnection(host.get());
                    Map<String, List<String>> rows = endpoint.snapshot(); history.capture(); Map<String, Map<String, ?>> before = realPreferences();
                    Rect back = backBounds(pairing); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
                    try {
                        SystemClock.sleep(4700);
                        assertEquals("A platform long click must not toggle the five-second gesture", !expected, DummySubPreview.isEnabled(context));
                        await(() -> pairing.isFinishing() || pairing.isDestroyed());
                        assertTrue("The hold must last five full seconds", SystemClock.uptimeMillis() - down >= 5000);
                    } finally { pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY()); }
                    await(() -> resumed(host.get()));
                    assertEquals(expected, DummySubPreview.isEnabled(context));
                    await(() -> (tagged(host.get(), "person:dummy_sub_preview") != null) == expected);
                    assertEquals("Holding Back must not open preview", 0, previews.getHits());
                    assertStateAfterNormalRosterReturn(before, rows);
                    scenario.recreate(); scenario.onActivity(host::set); await(() -> resumed(host.get()));
                    assertEquals(expected, DummySubPreview.isEnabled(context));
                    await(() -> (tagged(host.get(), "person:dummy_sub_preview") != null) == expected);
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
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "person:dummy_sub_preview") != null);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PersonActivity.class.getName(), null, false);
            try {
                tapTag("person:dummy_sub_preview");
                PersonActivity profile = (PersonActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(profile); await(() -> resumed(profile));
                Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture();
                previewTransportBaseline = endpoint.transportCalls();
                assertTrue(DummySubPreview.isAllowed(profile));
                assertNotNull(tagged(profile, "person_identity"));
                assertEquals("Dummy Sub", ((android.widget.TextView) tagged(profile, "person_name")).getText().toString());
                assertTrue("Internal Tasks has an explicit owned route", tagged(profile, "person_tab:tasks").isClickable());
                instrumentation.runOnMainSync(() -> {
                    assertScopedControls(profile.findViewById(android.R.id.content));
                    assertFalse(profile.findViewById(com.subhub.app.R.id.person_session_action).isEnabled());
                    assertNotNull(profile.findViewById(com.subhub.app.R.id.person_duration_control));
                });
                capture(profile, "dummy-preview-profile");
                tapTag("person_tab:connection");
                assertScopedControls(profile.findViewById(android.R.id.content));
                nativeBack(profile); await(() -> tagged(profile, "person_tab:contracts") != null);
                tapTag("person_tab:contracts");
                await(() -> tagged(profile, "contract_section:duration") != null);
                assertNotNull(tagged(profile, "person_contract_identity"));
                assertNotNull(tagged(profile, "person_contract_scope"));
                assertNotNull(tagged(profile, "person_contract_features"));
                instrumentation.runOnMainSync(() -> {
                    View details = tagged(profile, "contract_details:atmosphere");
                    assertNotNull(details);
                    assertTrue(hasText(details, context.getString(com.subhub.app.R.string.arrangement_detail_line,
                            context.getString(com.subhub.app.R.string.gag_title),
                            context.getString(com.subhub.app.R.string.gag_status_unknown))));
                });
                for (String section : new String[]{"censor", "limits", "wallet", "atmosphere", "apps", "duration"}) {
                    Class<? extends Activity> expected = PersonRoutes.sectionActivity(section);
                    Instrumentation.ActivityMonitor pageMonitor = instrumentation.addMonitor(expected.getName(), null, false);
                    try {
                        openContractEditor(profile, section);
                        Activity page = instrumentation.waitForMonitorWithTimeout(pageMonitor, 5000);
                        assertNotNull("Native section did not open: " + section, page);
                        assertEquals(expected, page.getClass()); await(() -> resumed(page));
                        assertNativeSection(page, section);
                        if ("atmosphere".equals(section)) browseAtmosphere(page);
                        else {
                            if ("censor".equals(section)) browseCensorDetails(page);
                            exerciseNativeInput(page);
                        }
                        if ("limits".equals(section)) onView(androidx.test.espresso.matcher.ViewMatchers.withId(
                                com.subhub.app.R.id.per_app_allowances_list)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
                        capture(page, "dummy-preview-" + section);
                        assertRealState(before, rows);
                        nativeBack(page); await(() -> resumed(profile));
                    } finally { instrumentation.removeMonitor(pageMonitor); }
                }
                assertRealState(before, rows); // Full comparison before leaving the preview boundary.
                nativeBack(profile); await(() -> resumed(host.get()));
                assertStateAfterNormalRosterReturn(before, rows);
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }

    @Test public void previewRecreationAndRoleLossStayGuarded() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent route = authorizedProfileIntent();
        Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        previewTransportBaseline = endpoint.transportCalls();
        // Take the worker witness only after the owned roster has finished and drained.
        instrumentation.waitForIdleSync(); long work = endpoint.completedEndpointWork();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(route)) {
            AtomicReference<PersonActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> resumed(host.get())); tapTag("person_tab:contracts");
            scenario.recreate(); scenario.onActivity(host::set); await(() -> resumed(host.get()));
            assertNotNull(tagged(host.get(), "person_contract_scope"));
            nativeBack(host.get()); await(() -> tagged(host.get(), "person_tab:contracts") != null);
            assertEquals("A preview profile must not submit any application endpoint work", work, endpoint.completedEndpointWork());
            ControllerPinManager.enterSubMode(); await(() -> host.get().isFinishing() || host.get().isDestroyed());
            assertFalse(DummySubPreview.isEnabled(context));
            assertTrue("Role loss hides rather than deletes owned data", internalFixture.data.store.read().visible);
        }
        assertRejected(route);
        ControllerPinManager.enterDomMode(); assertRejected(route); // A new role revision cannot revive an old route.
        assertFalse(DummySubPreview.toggle(context)); assertRejected(route);
        assertTrue(DummySubPreview.toggle(context)); assertRejected(route); // Nor can toggling back on.
        assertRealState(before, rows);
    }

    @Test public void censorEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("censor");
    }

    @Test public void limitsEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("limits");
    }

    @Test public void walletEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("wallet");
    }

    @Test public void atmosphereEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("atmosphere");
    }

    @Test public void appsEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("apps");
    }

    @Test public void durationEditorRecreatesAndRejectsForgedTargets() throws Exception {
        exerciseNativeEditor("duration");
    }

    private void exerciseNativeEditor(String section) throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile = authorizedProfileIntent();
        Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        previewTransportBaseline = endpoint.transportCalls();
        instrumentation.waitForIdleSync(); long work = endpoint.completedEndpointWork();
        Intent route = authorizedSectionIntent(profile, section);
        route.putExtra("feature_editor_token", "untrusted-restored-session")
                .putExtra("feature_editor_document", "{\"name\":\"UNTRUSTED DOCUMENT\"}")
                .putExtra("feature_editor_base", "{\"name\":\"UNTRUSTED BASE\"}");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(route)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> resumed(page.get())); assertNativeSection(page.get(), section);
            scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
            assertNativeSection(page.get(), section);
            if (!"atmosphere".equals(section)) exerciseNativeInput(page.get());
            assertInternalBackend(page.get());
        }
        assertRejected(new Intent(route).putExtra("section", "untrusted-section"));
        assertRejected(new Intent(route).setClass(context, PersonActivity.class));
        assertRejected(new Intent(route).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
        assertRejected(new Intent(route).putExtra(PersonRoutes.EXTRA_PERSON_ID, "remote:forged:target"));
        assertRejected(new Intent(context, PersonRoutes.sectionActivity(section))
                .putExtra(PersonRoutes.EXTRA_PERSON_ID, DummySubPreview.ID.value()));
        assertEquals("Preview, recreation and forged routes must not submit endpoint work", work, endpoint.completedEndpointWork());
        assertRealState(before, rows);
    }

    @Test public void profileAuthorityRejectsForgeryAndLiveEditorRevocation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile = authorizedProfileIntent();
        Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        previewTransportBaseline = endpoint.transportCalls();
        instrumentation.waitForIdleSync(); long work = endpoint.completedEndpointWork();
        assertRejected(new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, DummySubPreview.ID.value()));
        assertRejected(new Intent(profile).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
        assertEquals("Preview, recreation and forged routes must not submit endpoint work", work, endpoint.completedEndpointWork());
        assertRealState(before, rows);
        // The state witness drains the endpoint with its own no-op. Start the next work
        // boundary after that witness, before any live revocation-phase navigation.
        long revocationWork = endpoint.completedEndpointWork();
        // Verify a live settings page closes when its route is revoked, not merely on next launch.
        Intent censor = authorizedSectionIntent(profile, "censor");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(censor)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> resumed(page.get())); assertFalse(DummySubPreview.toggle(context));
            await(() -> page.get().isFinishing() || page.get().isDestroyed());
        }
        assertRejected(profile);
        assertRejected(censor);
        assertEquals("Live revocation and stale routes must not submit endpoint work", revocationWork, endpoint.completedEndpointWork());
        assertRealState(before, rows);
    }

    @Test public void internalDurationNativeTapPersistsAcrossReopenAndHide() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile = authorizedProfileIntent();
        Intent duration = authorizedSectionIntent(profile, "duration");
        Map<String, Map<String, ?>> before = realPreferences();
        Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(duration)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> resumed(page.get()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_1h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            await(() -> Long.valueOf(3_600_000L).equals(internalFixture.data.acknowledged().configuration().get("duration").get("duration")));
            scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
            assertEquals(3_600_000L, com.subhub.app.configuration.FeatureEditorSession.context(page.get())
                    .getSharedPreferences("subhub_service_duration_ui", 0).getLong("duration", -1));
        }
        assertFalse(DummySubPreview.toggle(context));
        assertTrue(DummySubPreview.toggle(context));
        assertRejected(duration);
        assertEquals(Long.valueOf(3_600_000L), internalFixture.data.store.read().configuration().get("duration").get("duration"));
        assertRealState(before, rows);
    }

    @Test public void internalProfileSaveCancelAndRecreationAreIndependent() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile = authorizedProfileIntent();
        AtomicReference<Intent> editor = new AtomicReference<>();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(profile)) {
            scenario.onActivity(page -> editor.set(DummySubPreview.childIntent(page, ProfileEditorActivity.class)));
        }
        String original = internalFixture.data.acknowledged().profile.name;
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(editor.get())) {
            onView(tag("profile_name:sub")).perform(androidx.test.espresso.action.ViewActions.replaceText("Cancelled internal name"));
            tapTag("profile_cancel");
        }
        assertEquals(original, internalFixture.data.store.read().profile.name);
        Map<String, Map<String, ?>> before = realPreferences();
        Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        try (ActivityScenario<ProfileEditorActivity> scenario = ActivityScenario.launch(editor.get())) {
            onView(tag("profile_name:sub")).perform(androidx.test.espresso.action.ViewActions.replaceText("Owned internal name"));
            scenario.recreate();
            onView(tag("profile_name:sub")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.withText("Owned internal name")));
            tapTag("profile_save");
            await(() -> "Owned internal name".equals(internalFixture.data.acknowledged().profile.name));
        }
        assertRealState(before, rows);
    }

    @Test public void internalTasksSubmitAndReviewWithoutChangingPhysicalRoleOrLedger() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        java.time.LocalDateTime start = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        com.subhub.app.tasks.TaskDefinition task = new com.subhub.app.tasks.TaskDefinition("owned_internal_task", "Internal completion",
                "Synthetic fixture only", start, java.time.ZoneId.systemDefault(), 300_000, Set.of(), true, false, null);
        Map<String, Map<String, ?>> before = realPreferences();
        Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        com.subhub.app.tasks.TaskRepository author = new com.subhub.app.tasks.TaskRepository(context, DummySubPreview.ID);
        author.create(task);
        Intent profile = authorizedProfileIntent();
        AtomicReference<Intent> route = new AtomicReference<>();
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(profile)) {
            scenario.onActivity(page -> route.set(DummySubPreview.childIntent(page, com.subhub.app.tasks.TasksActivity.class)));
        }
        String occurrence = task.occurrenceId(start.toLocalDate());
        try (ActivityScenario<com.subhub.app.tasks.TasksActivity> scenario = ActivityScenario.launch(route.get())) {
            AtomicReference<com.subhub.app.tasks.TasksActivity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> tagged(page.get(), "internal_tasks_switch_view") != null);
            tapTag("internal_tasks_switch_view");
            await(() -> tagged(page.get(), "task_open:" + occurrence) != null);
            tapTag("task_open:" + occurrence); tapTag("request:" + occurrence);
            onView(tag("tasks_text")).perform(androidx.test.espresso.action.ViewActions.replaceText("Owned proof"));
            tapTag("tasks_send");
            await(() -> tagged(page.get(), "task_detail:" + occurrence) != null || tagged(page.get(), "internal_tasks_switch_view") != null);
            assertTrue("Internal Sub view never changes physical controller mode", ControllerPinManager.isDomWorkspaceUnlocked());
        }
        com.subhub.app.tasks.TaskEngine.Occurrence pending = author.engine().occurrence(task.id, start.toLocalDate(), System.currentTimeMillis());
        assertEquals(com.subhub.app.tasks.TaskEngine.Status.PENDING, pending.status);
        author.act(task.id, start.toLocalDate(), com.subhub.app.tasks.TaskAction.Kind.ACCEPT, "", List.of(), pending.submission.id);
        assertEquals(com.subhub.app.tasks.TaskEngine.Status.ACCEPTED,
                author.engine().occurrence(task.id, start.toLocalDate(), System.currentTimeMillis()).status);
        assertFalse(DummySubPreview.toggle(context)); assertTrue(DummySubPreview.toggle(context));
        assertThrows(SecurityException.class, author::engine);
        assertEquals(com.subhub.app.tasks.TaskEngine.Status.ACCEPTED,
                new com.subhub.app.tasks.TaskRepository(context, DummySubPreview.ID).engine()
                        .occurrence(task.id, start.toLocalDate(), System.currentTimeMillis()).status);
        assertRealState(before, rows);
    }

    @Test public void internalPresentationBuildsOffMainAndRejectsStaleRevisionAndController() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        InternalSubData data = InternalSubData.get(context);
        com.subhub.app.internal.InternalSubRecord before = data.acknowledged();
        instrumentation.runOnMainSync(() -> {
            assertNull(data.peekPresentation());
            assertThrows(IllegalStateException.class, () -> data.loadPresentation(context, before));
        });
        PersonSnapshot first = data.loadPresentation(context, before);
        instrumentation.runOnMainSync(() -> assertSame(first, data.peekPresentation()));
        com.subhub.app.internal.InternalSubRecord after = data.store.saveProfile(before,
                new com.subhub.app.internal.InternalProfile("Updated internal", "", "", "", 0));
        instrumentation.runOnMainSync(() -> assertNull(data.peekPresentation()));
        data.loadPresentation(context, before); // An older worker result cannot become current again.
        instrumentation.runOnMainSync(() -> assertNull(data.peekPresentation()));
        PersonSnapshot latest = data.loadPresentation(context, after);
        instrumentation.runOnMainSync(() -> assertSame(latest, data.peekPresentation()));
        ControllerPinManager.enterSubMode();
        instrumentation.runOnMainSync(() -> assertNull(data.peekPresentation()));
        assertThrows(SecurityException.class, () -> data.loadPresentation(context, after));
        assertTrue(ControllerPinManager.verify(context, "2468"));
    }

    @Test public void internalFavoriteAndTasksOverviewRetainIdentityAcrossRecreation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Map<String, ?> favoriteBefore = new LinkedHashMap<>(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).getAll());
        try {
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0), Collections.emptyMap());
            try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
                AtomicReference<PeopleActivity> page = new AtomicReference<>(); scenario.onActivity(page::set);
                await(() -> tagged(page.get(), "person:dummy_sub_preview") != null);
                onView(tag("person:dummy_sub_preview")).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),
                        androidx.test.espresso.action.ViewActions.longClick());
                await(() -> FavoriteSubStore.identity(DummySubPreview.ID).equals(new FavoriteSubStore(context).identity()));
                scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
                await(() -> tagged(page.get(), "people_favorite:dummy_sub_preview") != null);
            }
            try (ActivityScenario<com.subhub.app.MainActivity> scenario = ActivityScenario.launch(com.subhub.app.MainActivity.class)) {
                AtomicReference<com.subhub.app.MainActivity> page = new AtomicReference<>(); scenario.onActivity(page::set);
                await(() -> tagged(page.get(), "home_favorite:dummy_sub_preview") != null);
            }
            try (ActivityScenario<com.subhub.app.tasks.TasksOverviewActivity> scenario = ActivityScenario.launch(com.subhub.app.tasks.TasksOverviewActivity.class)) {
                AtomicReference<com.subhub.app.tasks.TasksOverviewActivity> page = new AtomicReference<>(); scenario.onActivity(page::set);
                await(() -> tagged(page.get(), "tasks_profile:dummy_sub_preview") != null);
                scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
                await(() -> tagged(page.get(), "tasks_profile:dummy_sub_preview") != null);
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(com.subhub.app.tasks.TasksActivity.class.getName(), null, false);
                try {
                    tapTag("tasks_profile:dummy_sub_preview");
                    Activity tasks = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                    assertNotNull(tasks); await(() -> resumed(tasks)); assertTrue(DummySubPreview.isAllowed(tasks));
                    await(() -> tagged(tasks, "internal_tasks_switch_view") != null);
                    assertFalse(DummySubPreview.toggle(context));
                    await(() -> tasks.isFinishing() || tasks.isDestroyed()); await(() -> resumed(page.get()));
                    await(() -> tagged(page.get(), "tasks_profile:dummy_sub_preview") == null);
                    assertTrue(DummySubPreview.toggle(context));
                    assertTrue(new FavoriteSubStore(context).isFavorite(DummySubPreview.snapshot(context)));
                } finally { instrumentation.removeMonitor(monitor); }
            }
        } finally { SharedPreferenceTestRestore.restore(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0), favoriteBefore); }
    }

    @Test public void queuedInternalEditorSaveCannotPersistAfterControllerRevocation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent duration = authorizedSectionIntent(authorizedProfileIntent(), "duration");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        internalFixture.data.execute(() -> {
            entered.countDown();
            try { if (!release.await(60, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long original = (Long) internalFixture.data.store.read().configuration().get("duration").get("duration");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(duration)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_1h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            scenario.onActivity(activity -> com.subhub.app.configuration.FeatureEditorSession.saveState(
                    (androidx.appcompat.app.AppCompatActivity) activity, new android.os.Bundle()));
            ControllerPinManager.enterSubMode();
            release.countDown(); internalFixture.data.execute(drained::countDown);
            assertTrue(drained.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(Long.valueOf(original), internalFixture.data.store.read().configuration().get("duration").get("duration"));
            await(() -> page.get().isFinishing() || page.get().isDestroyed());
        } finally { release.countDown(); assertTrue(ControllerPinManager.verify(context, "2468")); }
    }

    @Test public void rapidInternalDurationEditAndBackDrainTheLatestStagedValueAfterAck() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent duration = authorizedSectionIntent(authorizedProfileIntent(), "duration");
        Map<String, Map<String, ?>> before = realPreferences();
        Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        internalFixture.data.execute(() -> {
            entered.countDown();
            try { if (!release.await(60, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(duration)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_1h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            scenario.onActivity(activity -> com.subhub.app.configuration.FeatureEditorSession.saveState(
                    (androidx.appcompat.app.AppCompatActivity) activity, new android.os.Bundle()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_24h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            nativeBack(page.get());
            await(() -> page.get().isDestroyed());
            release.countDown();
            await(() -> Long.valueOf(86_400_000L).equals(internalFixture.data.acknowledged().configuration().get("duration").get("duration")));
        } finally { release.countDown(); }
        assertRealState(before, rows);
    }

    @Test public void heldAckRotationSharesOneDrainAndAcceptsLaterEdits() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent duration=authorizedSectionIntent(authorizedProfileIntent(),"duration");
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        internalFixture.data.execute(()->{
            entered.countDown();
            try{if(!release.await(60,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture release timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        });
        assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
        try(ActivityScenario<Activity> scenario=ActivityScenario.launch(duration)){
            AtomicReference<Activity> page=new AtomicReference<>();scenario.onActivity(page::set);await(()->resumed(page.get()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_1h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());
            scenario.onActivity(activity->com.subhub.app.configuration.FeatureEditorSession.saveState(
                    (androidx.appcompat.app.AppCompatActivity)activity,new android.os.Bundle()));
            scenario.recreate();scenario.onActivity(page::set);await(()->resumed(page.get()));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_24h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_7d))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());
            // Let the replacement binding's real debounce execute while the first owner ACK is held.
            java.util.concurrent.CountDownLatch replacementTimer=new java.util.concurrent.CountDownLatch(1);
            instrumentation.runOnMainSync(()->new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(replacementTimer::countDown,700));
            assertTrue(replacementTimer.await(5,java.util.concurrent.TimeUnit.SECONDS));
            onView(tag("internal_configuration_save_status")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.remote_autosave_saving)));
            release.countDown();
            await(()->Long.valueOf(604_800_000L).equals(internalFixture.data.acknowledged().configuration().get("duration").get("duration")));
            await(()->context.getString(com.subhub.app.R.string.internal_sub_saved).contentEquals(
                    ((android.widget.TextView)tagged(page.get(),"internal_configuration_save_status")).getText()));
            onView(tag("internal_configuration_save_status")).check(androidx.test.espresso.assertion.ViewAssertions.matches(
                    androidx.test.espresso.matcher.ViewMatchers.withText(com.subhub.app.R.string.internal_sub_saved)));
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.commitment_timer_24h))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(),click());
            await(()->Long.valueOf(86_400_000L).equals(internalFixture.data.acknowledged().configuration().get("duration").get("duration")));
            nativeBack(page.get());await(()->page.get().isDestroyed());
        }finally{release.countDown();}
    }

    @Test public void failedEndpointCloseStillRestoresInternalGlobalsAndDisposesOwnedHandles() throws Exception {
        seedCleanupTask();
        AssertionError injected=new AssertionError("Injected endpoint close failure");
        OwnedFixtureCleanup cleanup=new OwnedFixtureCleanup();
        closeOwnedFixtures(cleanup,()->{endpoint.close();throw injected;},internalFixture);
        assertSame(injected,assertThrows(AssertionError.class,cleanup::finish));
        assertEquals(0,injected.getSuppressed().length);
        internalFixture.assertOriginalRestored();internalFixture.assertOwnedDisposed();
    }

    @Test public void failedFirstRegistryRestoreStillRestoresOtherRegistriesAndOwnedHandles() throws Exception {
        seedCleanupTask();
        java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
        AssertionError injected=new AssertionError("Injected first registry restore failure");
        AssertionError failure=assertThrows(AssertionError.class,()->internalFixture.closeWithRegistryRestore((preferences,original)->{
            if(attempts.getAndIncrement()==0)throw injected;
            SharedPreferenceTestRestore.restore(preferences,original);
        }));
        assertSame(injected,failure);assertEquals(0,failure.getSuppressed().length);
        assertEquals("Every independent registry restoration was attempted",3,attempts.get());
        internalFixture.assertOriginalRestored();internalFixture.assertOwnedDisposed();
    }
    private void seedCleanupTask() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        java.time.LocalDateTime start=java.time.LocalDateTime.now().withSecond(0).withNano(0);
        new com.subhub.app.tasks.TaskRepository(context,DummySubPreview.ID).create(new com.subhub.app.tasks.TaskDefinition(
                "owned_cleanup_task","Owned cleanup","Synthetic teardown witness",start,java.time.ZoneId.systemDefault(),
                300_000,Set.of(),true,false,null));
    }

    @Test public void ownerDataAckRefreshesInternalPresentationWithoutProfileMutation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        InternalSubData backend=internalFixture.data;
        com.subhub.app.internal.InternalSubRecord record=backend.acknowledged();
        PersonSnapshot before=backend.loadPresentation(context,record);
        long oldRevision=backend.presentationRevisionHint();
        backend.ownerData().submitTask(record.generation,access->access.assessTaskFailure(
                "ui_owned_ack_fixture",com.subhub.app.stats.OwnerObservation.capture(System.currentTimeMillis())))
                .get(10,java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(record,backend.acknowledged());
        assertTrue(backend.presentationRevisionHint()>oldRevision);
        instrumentation.runOnMainSync(()->assertNull(backend.peekPresentation()));
        PersonSnapshot after=backend.loadPresentation(context,record);
        assertNotSame(before,after);assertTrue(after.can(PersonSnapshot.Capability.VIEW_STATS));
        assertEquals(PersonSnapshot.Freshness.CURRENT,after.sync.stats);
        assertEquals(PersonSnapshot.Freshness.UNKNOWN,after.sync.runtime);
        instrumentation.runOnMainSync(()->assertSame(after,backend.peekPresentation()));
        assertFalse(DummySubPreview.toggle(context));
        instrumentation.runOnMainSync(()->assertNull(backend.peekPresentation()));
        assertThrows(SecurityException.class,()->backend.loadPresentation(context,record));
    }

    @Test public void signedInternalStatisticsRouteUsesOnlyInternalSourceAcrossRecreation() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile=authorizedProfileIntent();
        Map<String,Map<String,?>> before=realPreferences();Map<String,List<String>> rows=endpoint.snapshot();history.capture(true);
        AtomicReference<Intent> route=new AtomicReference<>();
        try(ActivityScenario<PersonActivity> scenario=ActivityScenario.launch(profile)){
            AtomicReference<PersonActivity> host=new AtomicReference<>();scenario.onActivity(host::set);
            await(()->resumed(host.get())&&host.get().hasWindowFocus());
            await(()->tagged(host.get(),"person_tab:stats")!=null);
            Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(com.subhub.app.stats.StatsActivity.class.getName(),null,false);
            try{
                tapTag("person_tab:stats");
                com.subhub.app.stats.StatsActivity stats=(com.subhub.app.stats.StatsActivity)instrumentation.waitForMonitorWithTimeout(monitor,5000);
                assertNotNull(stats);await(()->resumed(stats)&&stats.hasWindowFocus());
                assertTrue(DummySubPreview.isAllowed(stats));assertNotNull(tagged(stats,"internal_statistics"));
                route.set(new Intent(stats.getIntent()));nativeBack(stats);await(()->resumed(host.get())&&host.get().hasWindowFocus());
            }finally{instrumentation.removeMonitor(monitor);}
        }
        try(ActivityScenario<com.subhub.app.stats.StatsActivity> scenario=ActivityScenario.launch(route.get())){
            AtomicReference<com.subhub.app.stats.StatsActivity> stats=new AtomicReference<>();scenario.onActivity(stats::set);
            await(()->resumed(stats.get())&&stats.get().hasWindowFocus());
            scenario.recreate();scenario.onActivity(stats::set);await(()->resumed(stats.get())&&stats.get().hasWindowFocus());
            assertTrue(DummySubPreview.isAllowed(stats.get()));assertNotNull(tagged(stats.get(),"internal_statistics"));
            for(String field:new String[]{"remote","localFacts"}){
                java.lang.reflect.Field value=com.subhub.app.stats.StatsActivity.class.getDeclaredField(field);value.setAccessible(true);
                assertNull("Internal Stats must not acquire physical or remote facts",value.get(stats.get()));
            }
        }
        assertRejected(new Intent(context,com.subhub.app.stats.StatsActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID,DummySubPreview.ID.value()));
        assertRealState(before,rows);
    }

    @Test public void internalTaskNoticeRouteRejectsLockedControllerAndRetiredGeneration() throws Exception {
        assertTrue(DummySubPreview.toggle(context));String generation=internalFixture.data.acknowledged().generation;
        Intent original=DummySubPreview.taskNotificationIntent(context,generation);
        try(ActivityScenario<com.subhub.app.tasks.TasksActivity> scenario=ActivityScenario.launch(original)){
            AtomicReference<com.subhub.app.tasks.TasksActivity> page=new AtomicReference<>();scenario.onActivity(page::set);
            await(()->resumed(page.get())&&page.get().hasWindowFocus());assertTrue(DummySubPreview.isAllowed(page.get()));
        }
        ControllerPinManager.enterSubMode();
        assertRejected(original);
        assertRejected(DummySubPreview.taskNotificationIntent(context,generation));
        assertTrue(ControllerPinManager.verify(context,"2468"));
        assertRejected(original); // Unlocking again does not renew a route from the old controller epoch.
        Intent current=DummySubPreview.taskNotificationIntent(context,generation);
        assertFalse(DummySubPreview.toggle(context));assertTrue(DummySubPreview.toggle(context));
        assertRejected(current);
        assertThrows(SecurityException.class,()->DummySubPreview.taskNotificationIntent(context,generation));
    }

    private Intent authorizedProfileIntent() {
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> resumed(host.get()));
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PersonActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(() -> DummySubPreview.open(host.get()));
                PersonActivity page = (PersonActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(page); await(() -> resumed(page));
                Intent route = new Intent(page.getIntent());
                instrumentation.runOnMainSync(page::finish); await(() -> resumed(host.get()));
                return route;
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }
    private Intent authorizedSectionIntent(Intent profile, String section) {
        try (ActivityScenario<PersonActivity> scenario = ActivityScenario.launch(profile)) {
            AtomicReference<PersonActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> resumed(host.get()));
            if (!"duration".equals(section)) tapTag("person_tab:contracts");
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PersonRoutes.sectionActivity(section).getName(), null, false);
            try {
                if ("duration".equals(section)) tapTag("person_duration");
                else openContractEditor(host.get(), section);
                Activity page = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(page); await(() -> resumed(page));
                Intent route = new Intent(page.getIntent());
                nativeBack(page); await(() -> resumed(host.get()));
                return route;
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }
    private void assertRejected(Intent route) {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(route)) {
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (scenario.getState() != androidx.lifecycle.Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(20);
            assertEquals("Untrusted preview route must finish", androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.getState());
        }
    }
    private void openContractEditor(Activity profile, String section) {
        await(() -> tagged(profile, "contract_details:" + section) != null);
        View detail = tagged(profile, "contract_details:" + section);
        if (detail == null || detail.getVisibility() != View.VISIBLE) tapTag("contract_section:" + section);
        tapTag("contract_edit:" + section);
    }
    private void tapTag(String value) {
        onView(tag(value)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
    }
    private void nativeBack(Activity page) {
        Rect back = backBounds(page); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
        pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY());
    }
    private void assertNativeSection(Activity page, String section) {
        instrumentation.runOnMainSync(() -> {
            assertTrue("Settings keep their authorized preview identity", DummySubPreview.isAllowed(page));
            assertEquals(PersonRoutes.sectionActivity(section), page.getClass());
            assertScopedControls(page.findViewById(android.R.id.content));
            int witness;
            switch (section) {
                case "censor": witness = com.subhub.app.R.id.switch_border; break;
                case "limits": witness = com.subhub.app.R.id.total_limit_enabled; break;
                case "wallet": witness = com.subhub.app.R.id.wallet_editor; break;
                case "atmosphere": witness = com.subhub.app.R.id.whispers_card; break;
                case "apps": witness = com.subhub.app.R.id.person_apps_panel; break;
                default: witness = com.subhub.app.R.id.person_duration_control;
            }
            assertNotNull("Normal native layout is present: " + section, page.findViewById(witness));

        });
        assertInternalBackend(page);
    }
    private static boolean hasText(View view, String expected) {
        if (view instanceof android.widget.TextView && expected.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }
    private void assertInternalBackend(Activity page) {
        instrumentation.runOnMainSync(() -> {
            com.subhub.app.configuration.FeatureEditorSession session = com.subhub.app.configuration.FeatureEditorSession.scope(page);
            assertNotNull("An explicit internal scope is attached", session);
            assertTrue(com.subhub.app.configuration.FeatureEditorSession.internal(page));
            assertNull("Internal editor never holds a peer", session.peer);
            Context draft = com.subhub.app.configuration.FeatureEditorSession.context(page);
            assertTrue(draft instanceof com.subhub.app.internal.InternalSubContext);
            long controllerBefore = ControllerPinManager.domWorkspaceRevision();
            View latentRole = PrimaryHeader.editLockButton(page.findViewById(android.R.id.content));
            if (latentRole != null) latentRole.performClick();
            assertEquals("Hidden scoped role controls have no physical command capability", controllerBefore,
                    ControllerPinManager.domWorkspaceRevision());
            assertTrue(ControllerPinManager.isDomWorkspaceUnlocked());
            assertThrows(SecurityException.class, () -> draft.getSharedPreferences("subhub_controller_auth", 0));
            assertThrows(SecurityException.class, () -> draft.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0)
                    .edit().putString("controller_pin_hash", "forbidden").commit());
            assertThrows(SecurityException.class, () -> draft.getContentResolver());
            assertThrows(SecurityException.class, () -> draft.getDatabasePath("must-not-open.db"));
            try {
                java.lang.reflect.Field feature = session.getClass().getDeclaredField("feature"); feature.setAccessible(true);
                assertNull("Internal editing must not acquire a transport endpoint", feature.get(session));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
    }
    private void browseCensorDetails(Activity censor) throws Exception {
        int[][] disclosures = {{com.subhub.app.R.id.button_appearance_details, com.subhub.app.R.id.appearance_content},
                {com.subhub.app.R.id.button_capture_details, com.subhub.app.R.id.capture_options_content}};
        for (int[] disclosure : disclosures) {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(disclosure[0]))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, censor.findViewById(disclosure[1]).getVisibility());
                assertScopedControls(censor.findViewById(disclosure[1]));
            });
        }
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                com.subhub.app.settings.CensorPresetsActivity.class.getName(), null, false);
        try {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.button_censor_presets))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            Activity presets = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(presets); await(() -> resumed(presets)); assertInternalBackend(presets);
            AtomicReference<Rect> save = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                View button = tagged(presets, "preset:save"); assertNotNull(button); assertTrue(button.isEnabled());
                Rect bounds = new Rect(); assertTrue(button.getGlobalVisibleRect(bounds)); save.set(bounds);
            });
            Rect bounds = save.get(); long down = pointer(MotionEvent.ACTION_DOWN, 0, bounds.centerX(), bounds.centerY());
            pointer(MotionEvent.ACTION_UP, down, bounds.centerX(), bounds.centerY());
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(android.R.string.cancel)).perform(click());
            await(() -> resumed(presets) && presets.hasWindowFocus());
            capture(presets, "dummy-preview-presets");
            Activity recreatedPresets = exerciseNestedRecreation(presets, censor);
            nativeBack(recreatedPresets); await(() -> resumed(censor));
            // Return to the normal section's first visible controls for the retained frame.
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.button_appearance_details))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
        } finally { instrumentation.removeMonitor(monitor); }
    }
    private void browseAtmosphere(Activity atmosphere) throws Exception {
        for (boolean whispers : new boolean[]{true, false}) {
            Class<? extends Activity> type = whispers ? com.subhub.app.subliminal.SubliminalSettingsActivity.class
                    : com.subhub.app.popup.PopupStormActivity.class;
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(type.getName(), null, false);
            try {
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(whispers ? com.subhub.app.R.id.whispers_card
                        : com.subhub.app.R.id.popup_storm_card)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
                Activity page = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(page); await(() -> resumed(page));
                instrumentation.runOnMainSync(() -> assertScopedControls(page.findViewById(android.R.id.content)));
                assertInternalBackend(page); exerciseNativeInput(page); attemptMaterialSlider(page);
                capture(page, "dummy-preview-" + (whispers ? "subliminal" : "popup"));
                Activity recreatedPage = exerciseNestedRecreation(page, atmosphere);
                nativeBack(recreatedPage); await(() -> resumed(atmosphere));
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }
    private Activity exerciseNestedRecreation(Activity original, Activity parent) {
        await(() -> resumed(original));
        AtomicReference<Activity> replacement = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger task = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Intent> route = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            task.set(original.getTaskId()); route.set(new Intent(original.getIntent()));
            assertEquals("Nested child remains in its existing parent's task", task.get(), parent.getTaskId());
            assertFalse(parent.isFinishing()); assertFalse(parent.isDestroyed());
        });
        androidx.test.runner.lifecycle.ActivityLifecycleCallback observer = (activity, stage) -> {
            if (stage == Stage.RESUMED && activity != original
                    && activity.getClass() == original.getClass() && activity.getTaskId() == task.get())
                replacement.set(activity);
        };
        try {
            instrumentation.runOnMainSync(() -> {
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(observer);
                // Recreate the actual open child. A new ActivityScenario launch owns a separate
                // launch/cleanup lifecycle and cannot verify this retained parent's back stack.
                original.recreate();
            });
            await(() -> original.isDestroyed() && replacement.get() != null && resumed(replacement.get()));
            Activity page = replacement.get();
            instrumentation.runOnMainSync(() -> {
                assertNotSame("Recreation must replace the original child", original, page);
                assertEquals(original.getClass(), page.getClass());
                assertEquals(task.get(), page.getTaskId());
                assertEquals(route.get().getStringExtra(PersonRoutes.EXTRA_PERSON_ID),
                        page.getIntent().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
                assertEquals(route.get().getStringExtra("section"), page.getIntent().getStringExtra("section"));
                assertTrue("Recreated child keeps the authorized preview route", DummySubPreview.isAllowed(page));
                assertFalse("The existing parent must survive child recreation", parent.isDestroyed());
                assertFalse(parent.isFinishing());
                assertScopedControls(page.findViewById(android.R.id.content));
            });
            assertInternalBackend(page);
            if (!(page instanceof com.subhub.app.settings.CensorPresetsActivity)) {
                exerciseNativeInput(page); attemptMaterialSlider(page);
            } else instrumentation.runOnMainSync(() -> assertTrue(tagged(page, "preset:save").isEnabled()));
            return page;
        } finally {
            instrumentation.runOnMainSync(() -> ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(observer));
        }
    }
    private void attemptMaterialSlider(Activity page) {
        AtomicReference<com.google.android.material.slider.Slider> slider = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> slider.set(findSlider(page.findViewById(android.R.id.content))));
        assertNotNull("Normal atmosphere renderer includes its Material Slider", slider.get());
        onView(org.hamcrest.Matchers.sameInstance((View) slider.get()))
                .perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
        AtomicReference<Rect> bounds = new AtomicReference<>(); AtomicReference<Float> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            assertTrue(slider.get().isEnabled()); value.set(slider.get().getValue());
            Rect rect = new Rect(); assertTrue(slider.get().getGlobalVisibleRect(rect)); bounds.set(rect);
        });
        Rect rect = bounds.get(); long down = pointer(MotionEvent.ACTION_DOWN, 0, rect.left + 4, rect.centerY());
        pointer(MotionEvent.ACTION_MOVE, down, rect.right - 4, rect.centerY());
        pointer(MotionEvent.ACTION_UP, down, rect.right - 4, rect.centerY());
        instrumentation.runOnMainSync(() -> { assertTrue(slider.get().isEnabled()); slider.get().setValue(value.get()); });
    }
    private static com.google.android.material.slider.Slider findSlider(View view) {
        if (view instanceof com.google.android.material.slider.Slider) return (com.google.android.material.slider.Slider) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            com.google.android.material.slider.Slider found = findSlider(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    /** Use native focus/touch without inventing an unknown input for a conditional settings control. */
    private void exerciseNativeInput(Activity page) {
        AtomicReference<View> selected = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> selected.set(firstVisibleControl(page.findViewById(android.R.id.content))));
        if (selected.get() == null) return; // Conditional settings may have no enabled input until a choice is selected.
        assertTrue(com.subhub.app.configuration.FeatureEditorSession.canEdit((androidx.appcompat.app.AppCompatActivity) page,
                page.getIntent().getStringExtra("section")));
        onView(org.hamcrest.Matchers.sameInstance(selected.get())).perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
        assertInternalBackend(page);
    }
    private static View firstVisibleControl(View view) {
        Rect bounds = new Rect();
        if (isControl(view) && view.isShown() && view.getGlobalVisibleRect(bounds) && bounds.height() >= 24) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = firstVisibleControl(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static boolean isControl(View view) {
        return view instanceof EditText || view instanceof CompoundButton || view instanceof SeekBar || view instanceof Spinner
                || view instanceof com.google.android.material.slider.Slider;
    }
    private static String controlValue(View view) {
        if (view instanceof CompoundButton) return Boolean.toString(((CompoundButton) view).isChecked());
        if (view instanceof SeekBar) return Integer.toString(((SeekBar) view).getProgress());
        if (view instanceof Spinner) return Integer.toString(((Spinner) view).getSelectedItemPosition());
        if (view instanceof com.google.android.material.slider.Slider)
            return Float.toString(((com.google.android.material.slider.Slider) view).getValue());
        return ((EditText) view).getText().toString();
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
        await(() -> resumed(activity));
        AtomicReference<View> target = new AtomicReference<>();
        AtomicReference<android.widget.ScrollView> viewport = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View back = PrimaryHeader.backButton(activity.findViewById(android.R.id.content));
            assertNotNull(back); target.set(back);
            for (android.view.ViewParent parent = back.getParent(); parent != null; parent = parent.getParent())
                if (parent instanceof android.widget.ScrollView) {
                    viewport.set((android.widget.ScrollView) parent); break;
                }
        });
        // Normal settings headers scroll with their page. Reveal the actual Back control after
        // inspecting lower settings, without changing its visibility or invoking its listener.
        if (viewport.get() != null) onView(org.hamcrest.Matchers.sameInstance(target.get()))
                .perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
        await(() -> resumed(activity));
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            assertTrue("Back input requires the same foreground Activity", resumed(activity));
            View back = target.get();
            assertSame(back, PrimaryHeader.backButton(activity.findViewById(android.R.id.content)));
            Rect bounds = new Rect();
            assertTrue(back.getGlobalVisibleRect(bounds)); assertEquals(back.getWidth(), bounds.width());
            assertEquals(back.getHeight(), bounds.height());
            Rect window = new Rect(); back.getWindowVisibleDisplayFrame(window);
            assertTrue("Back must clear the visible window's system/IME boundaries", window.contains(bounds));
            if (viewport.get() != null) {
                Rect visible = new Rect(); assertTrue(viewport.get().getGlobalVisibleRect(visible));
                assertTrue("Back must be fully inside its visible scroll viewport", visible.contains(bounds));
            }
            View navigation = activity.findViewById(com.subhub.app.R.id.bottom_navigation);
            Rect nav = new Rect();
            if (navigation != null && navigation.getGlobalVisibleRect(nav))
                assertTrue("Back must clear floating navigation", bounds.bottom <= nav.top);
            result.set(bounds);
        }); return result.get();
    }
    private long pointer(int action, long down, float x, float y) {
        long now = SystemClock.uptimeMillis(); if (down == 0) down = now;
        MotionEvent event = MotionEvent.obtain(down, now, action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).injectInputEvent(event, true)); }
        finally { event.recycle(); } return down;
    }
    private Set<String> preferenceStores() {
        Set<String> names = new TreeSet<>(Arrays.asList(STORES));
        java.io.File folder = new java.io.File(context.getApplicationInfo().dataDir, "shared_prefs");
        java.io.File[] files = folder.listFiles();
        if (files != null) for (java.io.File file : files) if (file.getName().endsWith(".xml"))
            names.add(file.getName().substring(0, file.getName().length() - 4));
        return names;
    }
    private Map<String, Map<String, ?>> realPreferences() {
        Map<String, Map<String, ?>> result = new LinkedHashMap<>();
        for (String store : preferenceStores()) if (!store.equals("subhub_dummy_preview") && !store.equals("subhub_internal_registry") && !store.equals("subhub_internal_tasks_registry")) {
            Map<String, ?> values = new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll());
            if (!values.isEmpty()) result.put(store, values);
        }
        return result;
    }
    private void assertRealState(Map<String, Map<String, ?>> before, Map<String, List<String>> rows) throws Exception {
        assertTrue("Preview must not change profiles, settings, relationships or consent", before.equals(realPreferences()));
        assertRuntimeStateUnchanged(rows);
    }
    private void assertStateAfterNormalRosterReturn(Map<String, Map<String, ?>> before,
            Map<String, List<String>> rows) throws Exception {
        // The real roster re-acknowledges the relationship journal on each provider read.
        // Only that disk-barrier UUID may change; formal_seen, operation and every other value remain checked.
        assertTrue("Normal roster return may only refresh the relationship journal write witness",
                withoutRosterWriteWitness(before).equals(withoutRosterWriteWitness(realPreferences())));
        assertRuntimeStateUnchanged(rows);
    }
    private static Map<String, Map<String, ?>> withoutRosterWriteWitness(Map<String, Map<String, ?>> values) {
        Map<String, Map<String, ?>> result = new LinkedHashMap<>(values);
        String store = "subhub_relationship_presentation";
        if (values.containsKey(store)) {
            Map<String, Object> journal = new LinkedHashMap<>(values.get(store));
            journal.remove("write_witness");
            result.put(store, journal);
        }
        return result;
    }
    private void assertRuntimeStateUnchanged(Map<String, List<String>> rows) throws Exception {
        assertTrue("Preview must not create peer, invitation, session, configuration or receipt rows", rows.equals(endpoint.snapshot()));
        assertEquals("Actual SQLite write audit remains unchanged", rows.get("preview_write_audit").size(), endpoint.writeCount());
        if (previewTransportBaseline >= 0)
            assertEquals("Preview cannot invoke transport commands or invitation/network operations", previewTransportBaseline, endpoint.transportCalls());
        history.assertUnchanged();
        assertFalse(LocalSessionControls.isActive(context));
    }
    private static void assertScopedControls(View view) {
        assertNotNull("Native layout remains present", view);
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
