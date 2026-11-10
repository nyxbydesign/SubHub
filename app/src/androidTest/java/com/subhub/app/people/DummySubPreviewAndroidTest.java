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
    private com.subhub.app.stats.DummyPreviewHistoryWitness history;
    private int previewTransportBaseline = -1;
    private static final String[] STORES = {"subhub_dummy_preview", "subhub_profile_names",
            SettingsRepository.PREFERENCES_NAME, SubFeaturePolicy.STORE, "subhub_relationship_presentation",
            "subhub_service_duration_ui", "profile_configuration_identity", "subhub_extra_time_v1",
            "subhub_controller_auth", "subhub_peer_transport", "subhub_foreground_presence",
            com.subhub.app.penance.PenanceManager.PREFS_NAME};

    @Before public void prepare() throws Exception {
        for (String store : preferenceStores()) saved.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        assertFalse("Use an idle, owned UI guest", LocalSessionControls.isActive(context));
        assertTrue(context.getSharedPreferences("subhub_dummy_preview", 0).edit().clear().commit());
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        assertTrue(ControllerPinManager.verify(context, "2468"));
        assertTrue("Fixture role transition must respect the controller guard", DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        endpoint = new DummyPreviewEndpointFixture(context);
        history = new com.subhub.app.stats.DummyPreviewHistoryWitness(context);
        instrumentation.waitForIdleSync();
    }
    @After public void restore() throws Exception {
        integration.drainReaders();
        try { if (endpoint != null) endpoint.close(); }
        finally { for (String store : preferenceStores())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(store, 0),
                    saved.containsKey(store) ? saved.get(store) : Collections.emptyMap()); }
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
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "dummy_sub_card") != null);
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PersonActivity.class.getName(), null, false);
            try {
                tapTag("dummy_sub_card");
                PersonActivity profile = (PersonActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull(profile); await(() -> resumed(profile));
                Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture();
                previewTransportBaseline = endpoint.transportCalls();
                assertTrue(DummySubPreview.isAllowed(profile));
                assertNotNull(tagged(profile, "person_identity"));
                assertEquals("Dummy Sub", ((android.widget.TextView) tagged(profile, "person_name")).getText().toString());
                assertFalse("Tasks must not open a live journal", tagged(profile, "person_tab:tasks").isClickable());
                instrumentation.runOnMainSync(() -> {
                    assertReadOnly(profile.findViewById(android.R.id.content));
                    assertFalse(profile.findViewById(com.subhub.app.R.id.person_session_action).isEnabled());
                    assertNotNull(profile.findViewById(com.subhub.app.R.id.person_duration_control));
                });
                capture(profile, "dummy-preview-profile");
                tapTag("person_tab:connection");
                assertReadOnly(profile.findViewById(android.R.id.content));
                nativeBack(profile); await(() -> tagged(profile, "person_tab:contracts") != null);
                tapTag("person_tab:contracts");
                for (String section : new String[]{"censor", "limits", "wallet", "atmosphere", "apps", "duration"}) {
                    Class<? extends Activity> expected = PersonRoutes.sectionActivity(section);
                    Instrumentation.ActivityMonitor pageMonitor = instrumentation.addMonitor(expected.getName(), null, false);
                    try {
                        if ("duration".equals(section)) {
                            nativeBack(profile); await(() -> tagged(profile, "person_tab:contracts") != null);
                        }
                        tapTag(sectionTag(section));
                        Activity page = instrumentation.waitForMonitorWithTimeout(pageMonitor, 5000);
                        assertNotNull("Native section did not open: " + section, page);
                        assertEquals(expected, page.getClass()); await(() -> resumed(page));
                        assertNativeSection(page, section);
                        if ("atmosphere".equals(section)) browseAtmosphere(page);
                        else {
                            if ("censor".equals(section)) browseCensorDetails(page);
                            attemptDisabledNativeInput(page);
                        }
                        if ("limits".equals(section)) onView(androidx.test.espresso.matcher.ViewMatchers.withId(
                                com.subhub.app.R.id.per_app_allowances_list)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation());
                        capture(page, "dummy-preview-" + section);
                        assertRealState(before, rows);
                        nativeBack(page); await(() -> resumed(profile));
                    } finally { instrumentation.removeMonitor(pageMonitor); }
                }
                nativeBack(profile); await(() -> resumed(host.get()));
                assertRealState(before, rows);
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
            assertTrue("Role loss hides, rather than deletes, the UI preference", context.getSharedPreferences("subhub_dummy_preview", 0).getBoolean("enabled", false));
        }
        assertRejected(route);
        ControllerPinManager.enterDomMode(); assertRejected(route); // A new role revision cannot revive an old route.
        assertFalse(DummySubPreview.toggle(context)); assertRejected(route);
        assertTrue(DummySubPreview.toggle(context)); assertRejected(route); // Nor can toggling back on.
        assertRealState(before, rows);
    }

    @Test public void nativeEditorsRecreateWithoutAutosaveAndRejectStaleOrForgedTargets() throws Exception {
        assertTrue(DummySubPreview.toggle(context));
        Intent profile = authorizedProfileIntent();
        Map<String, Map<String, ?>> before = realPreferences(); Map<String, List<String>> rows = endpoint.snapshot(); history.capture(true);
        previewTransportBaseline = endpoint.transportCalls();
        instrumentation.waitForIdleSync(); long work = endpoint.completedEndpointWork();
        for (String section : new String[]{"censor", "limits", "wallet", "atmosphere", "apps", "duration"}) {
            Intent route = authorizedSectionIntent(profile, section);
            route.putExtra("feature_editor_token", "untrusted-restored-session")
                    .putExtra("feature_editor_document", "{\"name\":\"UNTRUSTED DOCUMENT\"}")
                    .putExtra("feature_editor_base", "{\"name\":\"UNTRUSTED BASE\"}");
            try (ActivityScenario<Activity> scenario = ActivityScenario.launch(route)) {
                AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
                await(() -> resumed(page.get())); assertNativeSection(page.get(), section);
                scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
                assertNativeSection(page.get(), section);
                if (!"atmosphere".equals(section)) attemptDisabledNativeInput(page.get());
                assertNoAutosave(page.get());
            }
            assertRejected(new Intent(route).putExtra("section", "untrusted-section"));
            assertRejected(new Intent(route).setClass(context, PersonActivity.class));
            assertRejected(new Intent(route).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
            assertRejected(new Intent(route).putExtra(PersonRoutes.EXTRA_PERSON_ID, "remote:forged:target"));
            assertRejected(new Intent(context, PersonRoutes.sectionActivity(section))
                    .putExtra(PersonRoutes.EXTRA_PERSON_ID, DummySubPreview.ID.value()));
        }
        assertRejected(new Intent(context, PersonActivity.class).putExtra(PersonRoutes.EXTRA_PERSON_ID, DummySubPreview.ID.value()));
        assertRejected(new Intent(profile).putExtra(PersonRoutes.EXTRA_PERSON_ID, PersonId.LOCAL.value()));
        assertEquals("Preview, recreation and forged routes must not submit endpoint work", work, endpoint.completedEndpointWork());
        assertRealState(before, rows);
        // Verify a live settings page closes when its route is revoked, not merely on next launch.
        Intent censor = authorizedSectionIntent(profile, "censor");
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(censor)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> resumed(page.get())); assertFalse(DummySubPreview.toggle(context));
            await(() -> page.get().isFinishing() || page.get().isDestroyed());
        }
        assertRejected(profile);
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
                tapTag(sectionTag(section));
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
    private static String sectionTag(String section) {
        return "apps".equals(section) ? "person_apps" : "duration".equals(section) ? "person_duration" : "person_section:" + section;
    }
    private void tapTag(String value) {
        onView(tag(value)).perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
    }
    private void nativeBack(Activity page) {
        Rect back = backBounds(page); long down = pointer(MotionEvent.ACTION_DOWN, 0, back.centerX(), back.centerY());
        pointer(MotionEvent.ACTION_UP, down, back.centerX(), back.centerY());
    }
    private void assertNativeSection(Activity page, String section) {
        if ("limits".equals(section) || "apps".equals(section))
            await(() -> hasText(page.findViewById(android.R.id.content), "Reader (sample)")
                    && hasText(page.findViewById(android.R.id.content), "Music (sample)"));
        instrumentation.runOnMainSync(() -> {
            assertTrue("Settings keep their authorized preview identity", DummySubPreview.isAllowed(page));
            assertEquals(PersonRoutes.sectionActivity(section), page.getClass());
            assertReadOnly(page.findViewById(android.R.id.content));
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
            if ("limits".equals(section)) try {
                java.lang.reflect.Field field = page.getClass().getDeclaredField("allowanceInputs"); field.setAccessible(true);
                Map<?, ?> allowances = (Map<?, ?>) field.get(page);
                assertEquals(2, allowances.size());
                assertEquals("45", ((EditText) allowances.get("preview.reader")).getText().toString());
                assertEquals("60", ((EditText) allowances.get("preview.music")).getText().toString());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        assertNoAutosave(page);
    }
    private static boolean hasText(View view, String expected) {
        if (view instanceof android.widget.TextView && expected.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }
    private void assertNoAutosave(Activity page) {
        instrumentation.runOnMainSync(() -> {
            com.subhub.app.configuration.FeatureEditorSession session = com.subhub.app.configuration.FeatureEditorSession.scope(page);
            assertNotNull("A dedicated preview scope is attached", session);
            assertTrue(com.subhub.app.configuration.FeatureEditorSession.preview(page));
            assertNull("Preview cannot hold a live peer", session.peer);
            Context draft = com.subhub.app.configuration.FeatureEditorSession.context(page);
            Set<String> stores = new TreeSet<>(Arrays.asList(STORES));
            for (String section : com.subhub.app.configuration.ConfigurationSchema.SECTIONS)
                stores.add(com.subhub.app.configuration.ConfigurationSchema.preferenceStore(section));
            for (String store : stores) {
                android.content.SharedPreferences prefs = draft.getSharedPreferences(store, 0);
                Map<String, ?> original = new LinkedHashMap<>(prefs.getAll());
                assertFalse("Frozen preview rejects commit", prefs.edit().putString("preview_write_probe", "forbidden").commit());
                prefs.edit().clear().apply();
                assertEquals("Preview preferences reject apply and clear", original, prefs.getAll());
            }
            assertEquals("Two synthetic apps, never the installed-app inventory", new TreeSet<>(Arrays.asList("preview.reader", "preview.music")),
                    draft.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getStringSet(
                            com.subhub.app.appmode.AppModeManager.KEY_INCLUDED_PACKAGES, Collections.emptySet()));
            assertEquals(45, draft.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getInt("app_timer_allowance_minutes:preview.reader", -1));
            assertEquals(60, draft.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getInt("app_timer_allowance_minutes:preview.music", -1));
            com.subhub.app.appmode.AppTimerManager timers = new com.subhub.app.appmode.AppTimerManager(draft);
            Set<String> selected = new LinkedHashSet<>(Arrays.asList("preview.reader", "preview.music"));
            long now = System.currentTimeMillis();
            timers.recordUsage("preview.reader", 120000L, selected, now);
            assertEquals(0L, timers.snapshot("preview.reader", now).appUsedMillis);
            assertEquals(0L, timers.snapshot("preview.reader", now).totalUsedMillis);
            for (com.subhub.app.appmode.AppTimerManager.UsageSnapshot usage : timers.snapshots(selected, now).values()) {
                assertEquals(0L, usage.appUsedMillis); assertEquals(0L, usage.totalUsedMillis);
            }
            timers.checkpointHistory(now);
            assertEquals(com.subhub.app.appmode.AppTimerManager.LimitStatus.NONE, timers.limitStatus("preview.reader", selected, now));
            assertThrows(SecurityException.class, () -> draft.getContentResolver());
            assertThrows(SecurityException.class, () -> draft.getDatabasePath("preview-must-not-open.db"));
            assertThrows(SecurityException.class, () -> draft.openFileInput("preview-must-not-read"));
            try {
                java.lang.reflect.Field feature = session.getClass().getDeclaredField("feature"); feature.setAccessible(true);
                assertNull("Preview must not acquire the application endpoint", feature.get(session));
                java.lang.reflect.Field bindings = session.getClass().getDeclaredField("bindings"); bindings.setAccessible(true);
                for (Object binding : ((Map<?, ?>) bindings.get(session)).values()) {
                    java.lang.reflect.Field autosave = binding.getClass().getDeclaredField("autosave"); autosave.setAccessible(true);
                    assertNull("A preview must not instantiate a live autosave", autosave.get(binding));
                }
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
                assertReadOnly(censor.findViewById(disclosure[1]));
            });
        }
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                com.subhub.app.settings.CensorPresetsActivity.class.getName(), null, false);
        try {
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(com.subhub.app.R.id.button_censor_presets))
                    .perform(com.subhub.app.NativeUiActions.revealAboveNavigation(), click());
            Activity presets = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(presets); await(() -> resumed(presets)); assertNoAutosave(presets);
            AtomicReference<Rect> save = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                View button = tagged(presets, "preset:save"); assertNotNull(button); assertFalse(button.isEnabled());
                Rect bounds = new Rect(); assertTrue(button.getGlobalVisibleRect(bounds)); save.set(bounds);
            });
            Rect bounds = save.get(); long down = pointer(MotionEvent.ACTION_DOWN, 0, bounds.centerX(), bounds.centerY());
            pointer(MotionEvent.ACTION_UP, down, bounds.centerX(), bounds.centerY());
            await(() -> resumed(presets)); // A disabled Save must not create the name dialog.
            capture(presets, "dummy-preview-presets");
            Intent nestedRoute = new Intent(presets.getIntent());
            nativeBack(presets); await(() -> resumed(censor));
            exerciseNestedRecreation(nestedRoute); await(() -> resumed(censor));
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
                instrumentation.runOnMainSync(() -> assertReadOnly(page.findViewById(android.R.id.content)));
                assertNoAutosave(page); attemptDisabledNativeInput(page); attemptMaterialSlider(page);
                capture(page, "dummy-preview-" + (whispers ? "subliminal" : "popup"));
                Intent nestedRoute = new Intent(page.getIntent());
                nativeBack(page); await(() -> resumed(atmosphere));
                exerciseNestedRecreation(nestedRoute); await(() -> resumed(atmosphere));
            } finally { instrumentation.removeMonitor(monitor); }
        }
    }
    private void exerciseNestedRecreation(Intent route) {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(route)) {
            AtomicReference<Activity> page = new AtomicReference<>(); scenario.onActivity(page::set);
            await(() -> resumed(page.get()));
            scenario.recreate(); scenario.onActivity(page::set); await(() -> resumed(page.get()));
            instrumentation.runOnMainSync(() -> assertReadOnly(page.get().findViewById(android.R.id.content)));
            assertNoAutosave(page.get());
            if (!(page.get() instanceof com.subhub.app.settings.CensorPresetsActivity)) {
                attemptDisabledNativeInput(page.get()); attemptMaterialSlider(page.get());
            }
            else instrumentation.runOnMainSync(() -> assertFalse(tagged(page.get(), "preset:save").isEnabled()));
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
            assertFalse(slider.get().isEnabled()); value.set(slider.get().getValue());
            Rect rect = new Rect(); assertTrue(slider.get().getGlobalVisibleRect(rect)); bounds.set(rect);
        });
        Rect rect = bounds.get(); long down = pointer(MotionEvent.ACTION_DOWN, 0, rect.left + 4, rect.centerY());
        pointer(MotionEvent.ACTION_MOVE, down, rect.right - 4, rect.centerY());
        pointer(MotionEvent.ACTION_UP, down, rect.right - 4, rect.centerY());
        instrumentation.runOnMainSync(() -> assertEquals(value.get(), Float.valueOf(slider.get().getValue())));
    }
    private static com.google.android.material.slider.Slider findSlider(View view) {
        if (view instanceof com.google.android.material.slider.Slider) return (com.google.android.material.slider.Slider) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            com.google.android.material.slider.Slider found = findSlider(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    /** Inject an actual touch into a visible disabled control, then verify its rendered value. */
    private void attemptDisabledNativeInput(Activity page) {
        AtomicReference<View> selected = new AtomicReference<>(); AtomicReference<String> value = new AtomicReference<>();
        AtomicReference<Rect> bounds = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View control = firstVisibleControl(page.findViewById(android.R.id.content));
            assertNotNull("A normal settings control must be visible", control); assertFalse(control.isEnabled());
            Rect rect = new Rect(); assertTrue(control.getGlobalVisibleRect(rect));
            selected.set(control); value.set(controlValue(control)); bounds.set(rect);
        });
        Rect rect = bounds.get(); long down = pointer(MotionEvent.ACTION_DOWN, 0, rect.centerX(), rect.centerY());
        pointer(MotionEvent.ACTION_UP, down, rect.centerX(), rect.centerY());
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertEquals("Native touch cannot edit a preview", value.get(), controlValue(selected.get()));
            assertReadOnly(page.findViewById(android.R.id.content));
        });
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
        for (String store : preferenceStores()) if (!store.equals("subhub_dummy_preview")) {
            Map<String, ?> values = new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll());
            if (!values.isEmpty()) result.put(store, values);
        }
        return result;
    }
    private void assertRealState(Map<String, Map<String, ?>> before, Map<String, List<String>> rows) throws Exception {
        assertTrue("Preview must not change profiles, settings, relationships or consent", before.equals(realPreferences()));
        assertTrue("Preview must not create peer, invitation, session, configuration or receipt rows", rows.equals(endpoint.snapshot()));
        assertEquals("Actual SQLite write audit remains unchanged", rows.get("preview_write_audit").size(), endpoint.writeCount());
        if (previewTransportBaseline >= 0)
            assertEquals("Preview cannot invoke transport commands or invitation/network operations", previewTransportBaseline, endpoint.transportCalls());
        history.assertUnchanged();
        assertFalse(LocalSessionControls.isActive(context));
    }
    private static void assertReadOnly(View view) {
        if (isControl(view))
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
