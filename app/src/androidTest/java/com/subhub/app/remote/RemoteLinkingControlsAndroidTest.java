package com.subhub.app.remote;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.people.FavoriteSubStore;
import com.subhub.app.people.People;
import com.subhub.app.people.PeopleActivity;
import com.subhub.app.people.ProfileStatsActivityAndroidTest;
import com.subhub.app.people.ProfileNames;
import com.subhub.app.relationships.RelationshipPanel;
import com.subhub.app.relationships.RelationshipPresentation;
import com.subhub.app.relationships.RelationshipRecoveryUi;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Real adapter, private local PIN and owned SQLite through the actual panel; no transport or OS-grant proof. */
public final class RemoteLinkingControlsAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private static final String JOURNAL = "subhub_relationship_presentation";
    private Context target, owned;
    private String prefix, database, journalIdentity;
    private final Set<String> privatePreferences = ConcurrentHashMap.newKeySet();
    private RemoteStore store;
    private RemoteFeature originalEndpoint;
    private FormalRelationshipProvider provider;
    private FormalRelationshipProvider receiptOwner;
    private RelationshipUi.Provider originalProvider;
    private Map<String, ?> originalJournal, originalFavorites;
    private Map<String, Object> journalStates;
    private Object journalLock, originalJournalState, originalApplication, originalHardcoreAuthority;
    private Field applicationField;

    @Before public void setup() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        originalProvider = RelationshipUi.provider();
        originalEndpoint = RemoteFeature.get(target); assertEndpointLive();
        applicationField = ControllerPinManager.class.getDeclaredField("application");
        applicationField.setAccessible(true); originalApplication = applicationField.get(null);
        originalHardcoreAuthority = hardcoreAuthorities().get(target.getApplicationContext());
        originalJournal = new LinkedHashMap<>(target.getSharedPreferences(JOURNAL, 0).getAll());
        originalFavorites = new LinkedHashMap<>(target.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).getAll());
        isolateJournalCache();
        assertTrue(target.getSharedPreferences(JOURNAL, 0).edit().clear().commit());
        assertTrue(target.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        assertTrue(target.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(target, "2468"));
        prefix = "native-local-link-" + UUID.randomUUID() + "-";
        owned = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                privatePreferences.add(name); return target.getSharedPreferences(prefix + name, mode);
            }
        };
        assertTrue(owned.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ProfileNames.set(owned, true, "Fixture Dom"));
        assertTrue(ProfileNames.set(owned, false, "Fixture Sub"));
        // The factory allocates a new physical file; no readiness flag, copied authority or SQL promotion.
        store = FormalRelationshipCoordinator.createOwnedFixture(owned);
        database = store.getDatabaseName();
        assertTrue(database.startsWith("formal-fixture-"));
        assertNotEquals(target.getDatabasePath("remote_sync_v1.db").getCanonicalFile(), store.resolvedDatabaseFile());
        provider = new FormalRelationshipProvider(owned, store); receiptOwner = provider;
        People.installKeyholder(new RemoteKeyholder(owned, store,
                new RemoteSecrets(owned, "owned-secrets", "subhub.test.native-local-link." + prefix)));
        assertTrue(ControllerPinManager.setPin(owned, "624871"));
        assertTrue(ControllerPinManager.verify(owned, "624871"));
        RelationshipUi.install(provider);
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualProviderRecreatesOriginalReviewThenExplicitlyLinksAndUnlinksOwnedLocalEdge() throws Exception {
        RelationshipRecoveryUi.RecoverySnapshot initial = provider.recoverySnapshot().get(10, TimeUnit.SECONDS);
        assertTrue("The actual fresh-store adapter must be supported", initial.snapshot.supported);
        assertEquals(2, initial.snapshot.localParticipants.size()); assertTrue(initial.snapshot.eligibleSubs.isEmpty());
        assertNull(initial.snapshot.governingDom); assertEquals(0, count("formal_operations"));
        Map<String, ?> consent = new LinkedHashMap<>(owned.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        try (ActivityScenario<PeopleActivity> page = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = new AtomicReference<>(); page.onActivity(host::set);
            await(() -> tag(host.get(), "relationship_prepare") != null);
            tap("relationship_prepare");
            await(() -> tag(host.get(), "relationship_confirm") != null);
            OperationRow original = row(); assertEquals("PREPARED", original.state);
            RelationshipPresentation.Saved saved = RelationshipPanel.journal(target).read();
            assertNotNull(saved); assertEquals(original.id, saved.operationId); assertEquals(original.digest, saved.digest);
            assertTrue(original.key.matches("fr1\\.[0-9a-f]{32}\\.(0|[1-9][0-9]{0,18})\\.[0-9a-f]{32}"));
            assertEquals(0, activeRelationships());

            // Replace the actual adapter as well as the Activity, so its old in-memory review cannot authorize a click.
            FormalRelationshipProvider retired = provider; provider = null; retired.close();
            provider = new FormalRelationshipProvider(owned, store); receiptOwner = provider; RelationshipUi.install(provider);
            page.recreate(); page.onActivity(host::set);
            await(() -> tag(host.get(), "relationship_resume") != null);
            page.onActivity(a -> assertNull(tag(a, "relationship_confirm")));
            assertRequest(original, row()); assertEquals("PREPARED", row().state); assertEquals(0, activeRelationships());
            tap("relationship_resume");
            await(() -> tag(host.get(), "relationship_confirm") != null);
            page.onActivity(a -> {
                RelationshipUi.LocalReview fresh = presentation(a).review();
                assertNotNull(fresh); assertEquals(original.id, fresh.operationId); assertEquals(original.digest, fresh.requestDigest);
                assertEquals(original.scope, fresh.scopeDigest); assertEquals(original.deadline, fresh.originalDeadline);
            });
            assertRequest(original, row()); assertEquals("PREPARED", row().state); assertEquals(0, activeRelationships());
            AtomicReference<View> beforeLinkPanel = new AtomicReference<>();
            page.onActivity(a -> beforeLinkPanel.set(tag(a, "relationship_panel")));
            tap("relationship_confirm"); authenticate();
            await(() -> tag(host.get(), "relationship_unlink") != null
                    && tag(host.get(), "relationship_panel") != beforeLinkPanel.get());
            assertRequest(original, row()); assertEquals("ACTIVE", row().state); assertEquals(1, activeRelationships());
            assertNull(RelationshipPanel.journal(target).read());
            RelationshipRecoveryUi.RecoverySnapshot linked = provider.recoverySnapshot().get(10, TimeUnit.SECONDS);
            assertEquals(1, linked.snapshot.eligibleSubs.size());
            RelationshipUi.Edge edge = linked.snapshot.eligibleSubs.get(0);
            String rosterTag = "person:" + edge.personId.value();
            await(() -> tag(host.get(), rosterTag) != null && tag(host.get(), "relationship_unlink") != null);
            assertEquals(RelationshipUi.Delivery.LOCAL, edge.delivery);
            assertNotNull(linked.snapshot.governingDom);
            assertEquals(edge.relationshipId, linked.snapshot.governingDom.relationshipId);
            AtomicReference<RelationshipPresentation> unlinkOwner = new AtomicReference<>();
            page.onActivity(a -> unlinkOwner.set(presentation(a)));
            tap("relationship_unlink"); authenticate();
            await(() -> statusAbsentUnlink(host.get()) && tag(host.get(), rosterTag) == null
                    && textShown(host.get().findViewById(android.R.id.content), host.get().getString(R.string.relationships_no_subs)),
                    () -> captureUnlinkTimeout(host.get(), unlinkOwner.get(), rosterTag));
            RelationshipRecoveryUi.RecoverySnapshot unlinked = provider.recoverySnapshot().get(10, TimeUnit.SECONDS);
            assertTrue(unlinked.snapshot.eligibleSubs.isEmpty()); assertNull(unlinked.snapshot.governingDom);
            assertEquals(0, activeRelationships()); assertEquals(0, count("formal_governing_slots"));
            assertEquals(1, count("formal_operations")); assertRequest(original, row());
            assertEquals("ACTIVE", row().state); // Original completed operation is history, not rewritten by Unlink.
            assertEquals(consent, owned.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        }
    }

    @After public void cleanup() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        boolean[] providerClosed = {provider == null}, receiptsCleaned = {false};
        clean(failures, () -> { if (originalProvider != null) RelationshipUi.install(originalProvider); });
        clean(failures, () -> { if (provider != null) provider.close(); providerClosed[0] = true; });
        clean(failures, () -> {
            assertTrue("Provider must close before owned receipt cleanup", providerClosed[0]);
            if (receiptOwner != null) receiptOwner.cleanupOwnedFixture(); receiptsCleaned[0] = true;
        });
        clean(failures, () -> { if (store != null && receiptsCleaned[0]) FormalRelationshipCoordinator.releaseOwnedFixture(store); });
        clean(failures, () -> { if (store != null) SubFeaturePolicy.unregisterHardcoreAuthority(owned, store); });
        clean(failures, () -> { if (store != null && providerClosed[0]) store.close(); });
        clean(failures, () -> { if (database != null && receiptsCleaned[0]) { assertTrue(database.startsWith("formal-fixture-")); assertTrue(target.deleteDatabase(database)); } });
        if (prefix != null && receiptsCleaned[0]) for (String name : privatePreferences)
            clean(failures, () -> assertTrue(target.getSharedPreferences(prefix + name, 0).edit().clear().commit()));
        clean(failures, () -> { if (originalJournal != null) SharedPreferenceTestRestore.restore(target.getSharedPreferences(JOURNAL, 0), originalJournal); });
        clean(failures, () -> { if (originalFavorites != null) SharedPreferenceTestRestore.restore(target.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0), originalFavorites); });
        clean(failures, () -> {
            if (journalStates != null) synchronized (journalLock) {
                if (originalJournalState == null) journalStates.remove(journalIdentity);
                else journalStates.put(journalIdentity, originalJournalState);
            }
        });
        clean(failures, () -> { if (applicationField != null) applicationField.set(null, originalApplication); });
        clean(failures, () -> { if (originalEndpoint != null) { assertSame(originalEndpoint, RemoteFeature.get(target)); assertEndpointLive(); } });
        clean(failures, () -> { if (target != null) assertSame(originalHardcoreAuthority, hardcoreAuthorities().get(target.getApplicationContext())); });
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned linking fixture restoration failed; recovery database=" + database + ", preferences=" + prefix);
            for (Throwable value : failures) failure.addSuppressed(value); throw failure;
        }
    }

    private static void authenticate() throws Exception {
        onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withText(R.string.controller_pin_unlock)).perform(click());
    }
    private static void tap(String value) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withTagValue(is(value))).perform(revealAboveNavigation(), click());
    }
    private static View tag(PeopleActivity activity, String value) {
        return activity.findViewById(android.R.id.content).findViewWithTag(value);
    }
    private static boolean textShown(View view, String text) {
        if (view instanceof TextView && view.isShown() && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (textShown(((ViewGroup) view).getChildAt(index), text)) return true;
        return false;
    }
    private static boolean statusAbsentUnlink(PeopleActivity activity) {
        return tag(activity, "relationship_unlink") == null && tag(activity, "relationship_prepare") != null;
    }
    private static RelationshipPresentation presentation(PeopleActivity activity) {
        try {
            Field field = RelationshipPanel.class.getDeclaredField("model"); field.setAccessible(true);
            return (RelationshipPresentation) field.get(tag(activity, "relationship_panel"));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void await(BooleanSupplier check) throws Exception {
        await(check, () -> "");
    }
    private static void await(BooleanSupplier check, java.util.concurrent.Callable<String> failureEvidence) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            AtomicBoolean ready = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ready.set(check.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(25);
        }
        fail("Actual linking panel did not settle " + failureEvidence.call());
    }
    private String captureUnlinkTimeout(PeopleActivity activity, RelationshipPresentation unlinkOwner, String rosterTag) throws Exception {
        JSONObject state = new JSONObject().put("scope", "Owned synthetic local-link fixture before Activity and SQL teardown")
                .put("elapsedRealtime", android.os.SystemClock.elapsedRealtime());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                View panel = tag(activity, "relationship_panel");
                RelationshipPresentation current = panel == null ? null : presentation(activity);
                state.put("activityState", activity.getLifecycle().getCurrentState().name())
                        .put("windowFocus", activity.hasWindowFocus()).put("finishing", activity.isFinishing())
                        .put("sameUnlinkOwner", current == unlinkOwner)
                        .put("unlinkOwner", modelState(unlinkOwner)).put("currentModel", modelState(current))
                        .put("statusAbsentUnlink", statusAbsentUnlink(activity))
                        .put("emptyRosterMessage", textShown(activity.findViewById(android.R.id.content),
                                activity.getString(R.string.relationships_no_subs)));
                JSONObject tags = new JSONObject();
                for (String name : new String[]{"relationship_panel", "relationship_prepare", "relationship_unlink",
                        "relationship_unlink_status", "relationship_unlink_refresh", "relationship_loading",
                        "relationship_unavailable", "relationship_refresh", "relationship_controller_required",
                        "relationship_governing_dom", "relationship_operation_status", rosterTag}) {
                    View view = tag(activity, name);
                    tags.put(name, view == null ? JSONObject.NULL : new JSONObject().put("shown", view.isShown())
                            .put("enabled", view.isEnabled()).put("attached", view.isAttachedToWindow()));
                }
                state.put("viewTags", tags);
            } catch (Exception | AssertionError observation) {
                try { state.put("uiReadFailure", observation.getClass().getSimpleName()); }
                catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
            }
        });
        // SQL stays on the instrumentation thread, outside the UI/authority callbacks.
        try {
            state.put("sql", new JSONObject().put("activeRelationships", activeRelationships())
                    .put("revokedRelationships", countWhere("formal_relationships", "state='REVOKED'"))
                    .put("governingSlots", count("formal_governing_slots"))
                    .put("operations", count("formal_operations"))
                    .put("activeOperations", countWhere("formal_operations", "state='ACTIVE'"))
                    .put("unlinkOutcomes", count("linking_unlinks")));
        } catch (Exception | AssertionError observation) { state.put("sqlReadFailure", observation.getClass().getSimpleName()); }
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "candidate-1");
        if (!run.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid evidence namespace");
        String label = "local-unlink-timeout-" + UUID.randomUUID();
        File directory = new File(target.getFilesDir(), "ui-v3/" + run);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create unlink evidence directory");
        File record = new File(directory, label + ".json");
        state.put("nativeCapture", label);
        Files.write(record.toPath(), state.toString(2).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        String pixels = label;
        try { ProfileStatsActivityAndroidTest.captureNativeProfile(activity, label, null); }
        catch (Exception | AssertionError observation) { pixels += ":" + observation.getClass().getSimpleName(); }
        return "evidence=" + record.getAbsolutePath() + "; pixels=" + pixels + "; state=" + state;
    }
    private static JSONObject modelState(RelationshipPresentation model) throws org.json.JSONException {
        if (model == null) return new JSONObject().put("present", false);
        RelationshipUi.Snapshot snapshot = model.snapshot();
        return new JSONObject().put("present", true).put("mode", model.mode().name()).put("busy", model.busy())
                .put("unlinkUnavailable", model.unlinkUnavailable()).put("epoch", model.reviewEpoch())
                .put("completedUnlinks", model.completedUnlinks()).put("rosterChanges", model.rosterChanges())
                .put("snapshotPresent", snapshot != null)
                .put("eligibleSubs", snapshot == null ? JSONObject.NULL : snapshot.eligibleSubs.size())
                .put("governingDom", snapshot == null ? JSONObject.NULL : snapshot.governingDom != null)
                .put("operationState", model.operation() == null ? JSONObject.NULL : model.operation().state.name());
    }
    private long countWhere(String table, String condition) {
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + condition, null)) {
                assertTrue(rows.moveToFirst()); return rows.getLong(0);
            }
        }
    }
    private long count(String table) {
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
                assertTrue(rows.moveToFirst()); return rows.getLong(0);
            }
        }
    }
    private long activeRelationships() {
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM formal_relationships WHERE state='ACTIVE'", null)) {
                assertTrue(rows.moveToFirst()); return rows.getLong(0);
            }
        }
    }
    private OperationRow row() {
        synchronized (store) {
            try (Cursor rows = store.getReadableDatabase().rawQuery(
                    "SELECT id,request_digest,scope_digest,original_revision,operation_key,deadline_wall,state FROM formal_operations", null)) {
                assertEquals(1, rows.getCount()); assertTrue(rows.moveToFirst()); return new OperationRow(rows);
            }
        }
    }
    private static final class OperationRow {
        final String id, digest, scope, revision, key, state; final long deadline;
        OperationRow(Cursor row) {
            id = row.getString(0); digest = row.getString(1); scope = row.getString(2); revision = row.getString(3);
            key = row.getString(4); deadline = row.getLong(5); state = row.getString(6);
        }
    }
    private static void assertRequest(OperationRow first, OperationRow current) {
        assertEquals(first.id, current.id); assertEquals(first.digest, current.digest); assertEquals(first.scope, current.scope);
        assertEquals(first.revision, current.revision); assertEquals(first.key, current.key); assertEquals(first.deadline, current.deadline);
    }
    @SuppressWarnings("unchecked") private void isolateJournalCache() throws Exception {
        Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK"); lock.setAccessible(true); journalLock = lock.get(null);
        Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES"); states.setAccessible(true);
        journalStates = (Map<String, Object>) states.get(null);
        journalIdentity = target.getApplicationInfo().dataDir + "/" + JOURNAL;
        synchronized (journalLock) { originalJournalState = journalStates.remove(journalIdentity); }
    }
    @SuppressWarnings("unchecked") private static Map<Context, RemoteStore> hardcoreAuthorities() throws Exception {
        Field field = SubFeaturePolicy.class.getDeclaredField("authorities"); field.setAccessible(true);
        return (Map<Context, RemoteStore>) field.get(null);
    }
    private void assertEndpointLive() throws Exception {
        Field field = RemoteFeature.class.getDeclaredField("sessions"); field.setAccessible(true);
        assertFalse(((ThreadPoolExecutor) field.get(originalEndpoint)).isShutdown());
    }
    private interface Cleanup { void run() throws Exception; }
    private static void clean(List<Throwable> failures, Cleanup action) {
        try { action.run(); } catch (Throwable failure) { failures.add(failure); }
    }
}
