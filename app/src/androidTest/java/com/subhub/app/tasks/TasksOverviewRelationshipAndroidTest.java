package com.subhub.app.tasks;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRepository;
import com.subhub.app.people.PersonRoutes;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import com.subhub.app.relationships.RelationshipPanel;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

/** Synthetic presentation fixtures create no link, approve no operation and launch no task editor. */
public final class TasksOverviewRelationshipAndroidTest {
    private final com.subhub.app.OwnedUiIntegrationFixture integration = new com.subhub.app.OwnedUiIntegrationFixture();
    @Rule public final RuleChain fixtures = RuleChain.outerRule(integration)
            .around(new UiAutomationStateRule());
    private static final String JOURNAL = "subhub_relationship_presentation";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private RelationshipUi.Provider originalProvider;
    private Map<String, ?> originalJournal, originalConsent;
    private Object journalLock, originalJournalState;
    private Map<String, Object> journalStates;
    private String journalKey;
    private boolean journalCaptured, journalEntryPresent;
    private final AtomicReference<Intent> opened = new AtomicReference<>();
    private Instrumentation.ActivityMonitor monitor;

    @Before public void prepare() throws Exception {
        originalProvider = RelationshipUi.provider();
        originalJournal = new LinkedHashMap<>(context.getSharedPreferences(JOURNAL, 0).getAll());
        originalConsent = new LinkedHashMap<>(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).getAll());
        Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK");
        Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES");
        lock.setAccessible(true); states.setAccessible(true);
        journalLock = lock.get(null);
        @SuppressWarnings("unchecked") Map<String, Object> retained = (Map<String, Object>) states.get(null);
        journalStates = retained;
        journalKey = context.getApplicationInfo().dataDir + "/" + JOURNAL;
        synchronized (journalLock) {
            journalEntryPresent = journalStates.containsKey(journalKey);
            originalJournalState = journalStates.remove(journalKey);
            journalCaptured = true;
            assertTrue(context.getSharedPreferences(JOURNAL, 0).edit().clear().commit());
        }
        // Keep local summary reads away from task scheduling/effects; this is a roster fixture.
        assertTrue(context.getSharedPreferences(SubFeaturePolicy.STORE, 0).edit()
                .putBoolean("tasks", false).putBoolean("hardcore_locally_consented", false).commit());
        assertTrue(context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        ControllerPinManager.initialize(context);
        assertTrue(ControllerPinManager.setPin(context, "2468"));
        ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
        People.installLocalProjection(ignored -> person(PersonId.LOCAL, "Fixture local Sub", PersonSnapshot.Connection.LOCAL));
        RelationshipUi.install(new Provider(snapshot(Collections.emptyList())));
        monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null && TasksActivity.class.getName().equals(intent.getComponent().getClassName())) {
                    opened.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        instrumentation.waitForIdleSync();
    }

    @After public void restore() throws Exception {
        integration.drainReaders();
        try {
            if (monitor != null) instrumentation.removeMonitor(monitor);
        } finally {
            try {
                if (originalProvider != null) RelationshipUi.install(originalProvider);
                if (journalCaptured) synchronized (journalLock) {
                    try { SharedPreferenceTestRestore.restore(context.getSharedPreferences(JOURNAL, 0), originalJournal); }
                    finally {
                        journalStates.remove(journalKey);
                        if (journalEntryPresent) journalStates.put(journalKey, originalJournalState);
                    }
                    assertSame(originalJournalState, journalStates.get(journalKey));
                    assertEquals(journalEntryPresent, journalStates.containsKey(journalKey));
                }
            } finally {
                if (originalConsent != null)
                    SharedPreferenceTestRestore.restore(context.getSharedPreferences(SubFeaturePolicy.STORE, 0), originalConsent);
            }
        }
    }

    @Test public void unpairedLocalSubIsAbsentFromTheFormalTasksRoster() throws Exception {
        assertTrue(People.repository(context).people().contains(PersonId.LOCAL));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitText(page, R.string.tasks_overview_empty);
            assertTargets(page, Collections.emptyList());
            page.recreate();
            awaitText(page, R.string.tasks_overview_empty);
            assertTargets(page, Collections.emptyList());
        }
    }

    /** Compatibility cannot turn a Both-role local profile into an outgoing relationship. */
    @Test public void unpairedLocalSubIsAbsentFromTheLegacyCompatibilityRoster() throws Exception {
        RelationshipUi.install(RelationshipUi.unavailable());
        assertTrue(People.repository(context).people().contains(PersonId.LOCAL));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitText(page, R.string.tasks_overview_empty);
            assertTargets(page, Collections.emptyList());
        }
    }

    @Test public void explicitApprovedActiveLocalEdgeIsRetainedAndNativeTapKeepsLocalTarget() throws Exception {
        RelationshipUi.install(new Provider(snapshot(Collections.singletonList(edge(PersonId.LOCAL, 10,
                RelationshipUi.Delivery.LOCAL, RelationshipUi.Availability.AVAILABLE)))));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(PersonId.LOCAL));
            assertTargets(page, Collections.singletonList(PersonId.LOCAL));
            tap(button(PersonId.LOCAL));
            assertOpened(PersonId.LOCAL);
            opened.set(null);
            tap(row(PersonId.LOCAL));
            assertOpened(PersonId.LOCAL);
        }
    }

    @Test public void approvedPeersKeepCoordinatorOrderOfflineMembershipAndExactGeneration() throws Exception {
        PersonSnapshot first = person(peer("a", "generation-2"), "Zulu offline", PersonSnapshot.Connection.OFFLINE);
        PersonSnapshot second = person(peer("b", "generation-9"), "Alpha connected", PersonSnapshot.Connection.CONNECTED);
        PersonSnapshot old = person(peer("a", "generation-1"), "Old generation", PersonSnapshot.Connection.PAIRED);
        People.installRemoteRepository(repository(Arrays.asList(second, old, first)));
        RelationshipUi.install(new Provider(snapshot(Arrays.asList(
                edge(first.id, 10, RelationshipUi.Delivery.PEER, RelationshipUi.Availability.OFFLINE),
                edge(second.id, 20, RelationshipUi.Delivery.PEER, RelationshipUi.Availability.AVAILABLE)))));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(second.id));
            assertTargets(page, Arrays.asList(first.id, second.id));
            tap(button(first.id)); assertOpened(first.id);
            opened.set(null);
            tap(row(second.id)); assertOpened(second.id);
        }
    }

    @Test public void failedFormalProviderThenDefaultCannotRestoreLegacyLocalRoster() throws Exception {
        Provider provider = new Provider(snapshot(Collections.emptyList()));
        RelationshipUi.install(provider);
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitText(page, R.string.tasks_overview_empty);
            CompletableFuture<RelationshipUi.Snapshot> failure = new CompletableFuture<>();
            failure.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE));
            provider.value = failure;
            page.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            page.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            awaitText(page, R.string.role_person_unavailable);
            assertTargets(page, Collections.emptyList());
            RelationshipUi.install(RelationshipUi.unavailable());
            page.recreate();
            awaitText(page, R.string.role_person_unavailable);
            assertTargets(page, Collections.emptyList());
            assertNull(opened.get());
        }
    }

    @Test public void providerReplacementDuringSnapshotLoadRejectsTheOldRoster() throws Exception {
        Provider provider = new Provider(snapshot(Collections.emptyList()));
        CompletableFuture<RelationshipUi.Snapshot> held = new CompletableFuture<>();
        provider.value = held;
        RelationshipUi.install(provider);
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            assertTrue(provider.requested.await(3, TimeUnit.SECONDS));
            RelationshipUi.install(new Provider(snapshot(Collections.emptyList())));
            held.complete(snapshot(Collections.singletonList(edge(PersonId.LOCAL, 10,
                    RelationshipUi.Delivery.LOCAL, RelationshipUi.Availability.AVAILABLE))));
            awaitText(page, R.string.role_person_unavailable);
            assertTargets(page, Collections.emptyList());
            assertNull(opened.get());
        } finally { held.complete(snapshot(Collections.emptyList())); }
    }

    @Test public void providerReplacementDuringPersonLoadCannotRenderAnOldPeer() throws Exception {
        PersonSnapshot target = person(peer("c", "generation-3"), "Fixture peer", PersonSnapshot.Connection.OFFLINE);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return Collections.singletonList(target.id); }
            public PersonSnapshot load(PersonId id) throws Exception {
                assertEquals(target.id, id); entered.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture load timed out");
                return target;
            }
        });
        RelationshipUi.install(new Provider(snapshot(Collections.singletonList(edge(target.id, 10,
                RelationshipUi.Delivery.PEER, RelationshipUi.Availability.OFFLINE)))));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            RelationshipUi.install(new Provider(snapshot(Collections.emptyList())));
            release.countDown();
            awaitText(page, R.string.role_person_unavailable);
            assertTargets(page, Collections.emptyList());
        } finally { release.countDown(); }
    }

    @Test public void nativeViewAllTapRejectsAReplacedProviderEvenWithTheSameRoster() throws Exception {
        rejectStaleTap(false, false);
    }

    @Test public void sameProviderRemovingLocalDuringPersonLoadCannotRenderItsOldSummary() throws Exception {
        rejectLocalLoadRace(false);
    }

    @Test public void sameProviderRelinkingLocalDuringPersonLoadCannotRenderItsOldSummary() throws Exception {
        rejectLocalLoadRace(true);
    }

    private void rejectLocalLoadRace(boolean relink) throws Exception {
        RelationshipUi.Edge original = edge(PersonId.LOCAL, 10,
                RelationshipUi.Delivery.LOCAL, RelationshipUi.Availability.AVAILABLE);
        Provider provider = new Provider(snapshot(Collections.singletonList(original)));
        RelationshipUi.install(provider);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        People.installLocalProjection(ignored -> {
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture load timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
            }
            return person(PersonId.LOCAL, "Fixture local Sub", PersonSnapshot.Connection.LOCAL);
        });
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            RelationshipUi.Edge replacement = new RelationshipUi.Edge(original.relationshipId, "replacement-generation",
                    original.domParticipantRef, original.subParticipantRef, original.delivery, "ACTIVE",
                    original.insertionOrder, original.personId, original.availability, original.freshness);
            provider.value = CompletableFuture.completedFuture(snapshot(relink
                    ? Collections.singletonList(replacement) : Collections.emptyList()));
            release.countDown();
            awaitText(page, R.string.role_person_unavailable);
            assertTrue("Summary load must reobserve the same provider", provider.reads.get() >= 2);
            assertTargets(page, Collections.emptyList());
            assertNull("Retired LOCAL summary must not route to Tasks", opened.get());
        } finally { release.countDown(); }
    }

    @Test public void nativeIdentityTapRejectsTheDefaultProviderAfterFormalAuthority() throws Exception {
        rejectStaleTap(true, true);
    }

    @Test public void sameProviderRemovingApprovedLocalEdgeRejectsNativeViewAllTap() throws Exception {
        rejectChangedLocalEdge(false);
    }

    @Test public void sameProviderRelinkingLocalWithANewGenerationRejectsNativeIdentityTap() throws Exception {
        rejectChangedLocalEdge(true);
    }

    private void rejectChangedLocalEdge(boolean relink) throws Exception {
        RelationshipUi.Edge original = edge(PersonId.LOCAL, 10,
                RelationshipUi.Delivery.LOCAL, RelationshipUi.Availability.AVAILABLE);
        Provider provider = new Provider(snapshot(Collections.singletonList(original)));
        RelationshipUi.install(provider);
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(PersonId.LOCAL));
            RelationshipUi.Edge replacement = new RelationshipUi.Edge(original.relationshipId, "replacement-generation",
                    original.domParticipantRef, original.subParticipantRef, original.delivery, "ACTIVE",
                    original.insertionOrder, original.personId, original.availability, original.freshness);
            provider.value = CompletableFuture.completedFuture(snapshot(relink
                    ? Collections.singletonList(replacement) : Collections.emptyList()));
            int readsBeforeTap = provider.reads.get();
            tap(relink ? row(PersonId.LOCAL) : button(PersonId.LOCAL));
            awaitText(page, R.string.role_person_unavailable);
            assertTrue("Tap must reobserve the same provider", provider.reads.get() > readsBeforeTap);
            assertTargets(page, Collections.emptyList());
            assertNull("Removed or replaced LOCAL edge must not route to self Tasks", opened.get());
        }
    }

    @Test public void pendingNativeOpenCannotOutliveARefreshEpoch() throws Exception {
        RelationshipUi.Edge local = edge(PersonId.LOCAL, 10,
                RelationshipUi.Delivery.LOCAL, RelationshipUi.Availability.AVAILABLE);
        Provider provider = new Provider(snapshot(Collections.singletonList(local)));
        RelationshipUi.install(provider);
        CompletableFuture<RelationshipUi.Snapshot> held = new CompletableFuture<>();
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(PersonId.LOCAL));
            provider.value = held;
            int readsBeforeTap = provider.reads.get();
            tap(button(PersonId.LOCAL));
            await(() -> provider.reads.get() > readsBeforeTap);
            provider.value = CompletableFuture.completedFuture(snapshot(Collections.emptyList()));
            TaskRuntime.get(context).store.changed();
            awaitText(page, R.string.tasks_overview_empty);
            held.complete(snapshot(Collections.singletonList(local)));
            instrumentation.waitForIdleSync();
            assertTargets(page, Collections.emptyList());
            assertNull("A refreshed page must retire its old pending open", opened.get());
        } finally { held.complete(snapshot(Collections.emptyList())); }
    }

    @Test public void explicitLocalSubTaskTargetRemainsAvailableWithoutAFormalEdge() {
        ControllerPinManager.enterSubMode();
        assertTrue(DeviceRolePreferences.supportsSub(context));
        assertEquals("local", new TaskRepository(context, PersonId.LOCAL).target().scope);
    }

    @Test public void nativeTapCannotUseTheRosterAfterDomPinAccessIsRevoked() throws Exception {
        PersonSnapshot target = person(peer("e", "generation-5"), "Fixture peer", PersonSnapshot.Connection.OFFLINE);
        People.installRemoteRepository(repository(Collections.singletonList(target)));
        RelationshipUi.install(new Provider(snapshot(Collections.singletonList(edge(target.id, 10,
                RelationshipUi.Delivery.PEER, RelationshipUi.Availability.OFFLINE)))));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(target.id));
            page.onActivity(a -> ControllerPinManager.enterSubMode());
            tap(button(target.id));
            assertTargets(page, Collections.emptyList());
            assertNull("Locked workspace must not start Tasks", opened.get());
        }
    }

    private void rejectStaleTap(boolean identity, boolean unavailable) throws Exception {
        PersonSnapshot target = person(peer("d", "generation-4"), "Fixture peer", PersonSnapshot.Connection.OFFLINE);
        People.installRemoteRepository(repository(Collections.singletonList(target)));
        RelationshipUi.Snapshot roster = snapshot(Collections.singletonList(edge(target.id, 10,
                RelationshipUi.Delivery.PEER, RelationshipUi.Availability.OFFLINE)));
        RelationshipUi.install(new Provider(roster));
        try (ActivityScenario<TasksOverviewActivity> page = ActivityScenario.launch(TasksOverviewActivity.class)) {
            awaitTag(page, button(target.id));
            RelationshipUi.install(unavailable ? RelationshipUi.unavailable() : new Provider(roster));
            tap(identity ? row(target.id) : button(target.id));
            awaitText(page, R.string.role_person_unavailable);
            assertTargets(page, Collections.emptyList());
            assertNull("Stale roster must not start Tasks", opened.get());
        }
    }

    private static PersonId peer(String digit, String generation) {
        StringBuilder identity = new StringBuilder("remote_");
        for (int i = 0; i < 64; i++) identity.append(digit);
        return new PersonId(identity + "_" + generation);
    }

    private static PersonSnapshot person(PersonId id, String name, PersonSnapshot.Connection connection) {
        return new PersonSnapshot(id, name, "Fixture device", "", "", connection,
                PersonSnapshot.Freshness.STALE, 0, EnumSet.of(PersonSnapshot.Capability.VIEW_STATS),
                Collections.emptyList(), Collections.emptyList(), "EUR", 0, 0, 0, 0, false)
                .withProfile(ProfileData.empty(name), Collections.emptyMap());
    }

    private static PersonRepository repository(List<PersonSnapshot> people) {
        return new PersonRepository() {
            public List<PersonId> people() {
                List<PersonId> ids = new ArrayList<>();
                for (PersonSnapshot person : people) ids.add(person.id);
                return ids;
            }
            public PersonSnapshot load(PersonId id) {
                for (PersonSnapshot person : people) if (person.id.equals(id)) return person;
                throw new IllegalArgumentException("Unexpected fixture target");
            }
        };
    }

    private static RelationshipUi.Edge edge(PersonId id, long order, RelationshipUi.Delivery delivery,
            RelationshipUi.Availability availability) {
        return new RelationshipUi.Edge("fixture-relationship-" + order, "fixture-generation-" + order,
                "fixture-dom", "fixture-sub-" + order, delivery, "ACTIVE", order, id, availability,
                PersonSnapshot.Freshness.STALE);
    }

    private static RelationshipUi.Snapshot snapshot(List<RelationshipUi.Edge> edges) {
        return new RelationshipUi.Snapshot(true, "fixture-revision", Arrays.asList(
                new RelationshipUi.Participant("fixture-dom", RelationshipUi.Role.DOM, ProfileData.empty("Fixture Dom")),
                new RelationshipUi.Participant("fixture-sub-10", RelationshipUi.Role.SUB, ProfileData.empty("Fixture Sub"))),
                edges, null, Collections.emptyList());
    }

    private static final class Provider implements RelationshipUi.Provider {
        volatile CompletableFuture<RelationshipUi.Snapshot> value;
        final CountDownLatch requested = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger();
        Provider(RelationshipUi.Snapshot snapshot) { value = CompletableFuture.completedFuture(snapshot); }
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() {
            reads.incrementAndGet(); requested.countDown(); return value;
        }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) {
            throw new AssertionError("Roster fixture must not prepare a relationship");
        }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) {
            throw new AssertionError("Roster fixture must not approve a relationship");
        }
        public CompletableFuture<RelationshipUi.Operation> resume(String id) { throw new AssertionError("Unexpected relationship mutation"); }
        public CompletableFuture<RelationshipUi.Operation> cancel(String id) { throw new AssertionError("Unexpected relationship mutation"); }
    }

    private void assertOpened(PersonId expected) throws Exception {
        await(() -> opened.get() != null);
        assertNotNull("Native tap must start the selected Tasks destination", opened.get());
        assertEquals(expected.value(), opened.get().getStringExtra(PersonRoutes.EXTRA_PERSON_ID));
    }

    private static String button(PersonId id) { return "tasks_profile:" + id.value(); }
    private static String row(PersonId id) { return "tasks_profile_row:" + id.value(); }
    private static void tap(String tag) { onView(withTagValue(is(tag))).perform(revealAboveNavigation(), click()); }

    private void awaitTag(ActivityScenario<TasksOverviewActivity> page, String tag) throws Exception {
        await(() -> {
            boolean[] found = {false};
            page.onActivity(a -> found[0] = a.findViewById(R.id.role_page_body).findViewWithTag(tag) != null);
            return found[0];
        });
    }

    private void awaitText(ActivityScenario<TasksOverviewActivity> page, int string) throws Exception {
        await(() -> {
            boolean[] found = {false};
            page.onActivity(a -> found[0] = hasText(a.findViewById(R.id.role_page_body), a.getString(string)));
            return found[0];
        });
    }

    private static boolean hasText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasText(((ViewGroup) view).getChildAt(i), expected)) return true;
        return false;
    }

    private static void assertTargets(ActivityScenario<TasksOverviewActivity> page, List<PersonId> expected) {
        page.onActivity(a -> {
            List<String> actual = new ArrayList<>();
            collectTargets(a.findViewById(R.id.role_page_body), actual);
            List<String> ids = new ArrayList<>();
            for (PersonId id : expected) ids.add(button(id));
            assertEquals(ids, actual);
        });
    }

    private static void collectTargets(View view, List<String> targets) {
        if (view.getTag() instanceof String && ((String) view.getTag()).startsWith("tasks_profile:"))
            targets.add((String) view.getTag());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            collectTargets(((ViewGroup) view).getChildAt(i), targets);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < end) {
            instrumentation.waitForIdleSync();
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(20);
        }
        assertTrue("Timed out waiting for the Tasks roster", condition.getAsBoolean());
    }
}
