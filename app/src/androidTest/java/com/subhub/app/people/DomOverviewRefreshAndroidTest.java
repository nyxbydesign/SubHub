package com.subhub.app.people;

import android.app.UiAutomation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.NativeUiActions;
import com.subhub.app.R;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.relationships.RelationshipPanel;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.*;

/** Actual Home timer retries must not detach unrelated local cards when relationship reads fail. */
@RunWith(AndroidJUnit4.class)
public final class DomOverviewRefreshAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final com.subhub.app.UiAutomationStateRule automation = new com.subhub.app.UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private final FailingRelationships relationships = new FailingRelationships();
    private RelationshipUi.Provider originalRelationships;
    private ActivityRepository originalActivity;
    private Object journalLock, originalJournalState;
    private Map<Object, Object> journalStates;
    private String journalKey;
    private boolean journalStateExisted;
    private final AtomicReference<AssertionError> observerFailure = new AtomicReference<>();

    @Before @SuppressWarnings("unchecked") public void prepare() throws Exception {
        assertFalse("Use an idle owned UI guest", LocalSessionControls.isActive(context));
        // Connect before target Views exist; do not suppress services or adopt/grant permissions.
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        originalRelationships = RelationshipUi.provider();
        originalActivity = ActivitySources.repository();
        for (String store : new String[] { "subhub_relationship_presentation", FavoriteSubStore.PREFERENCES_NAME,
                "betablocker_stats", "betablocker_achievements", "subhub_service_duration_ui" })
            saved.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK"); lock.setAccessible(true);
        Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES"); states.setAccessible(true);
        journalLock = lock.get(null); journalStates = (Map<Object, Object>) states.get(null);
        journalKey = context.getApplicationInfo().dataDir + "/subhub_relationship_presentation";
        synchronized (journalLock) {
            journalStateExisted = journalStates.containsKey(journalKey);
            originalJournalState = journalStates.remove(journalKey);
        }
        assertTrue(context.getSharedPreferences("subhub_relationship_presentation", 0).edit().clear().commit());
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertTrue(ControllerPinManager.setPin(context, "5729"));
            assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
            OnboardingState.complete(context);
            ControllerPinManager.enterSubMode();
            People.installKeyholder(KeyholderPresentationAndroidTest.provider(KeyholderProvider.State.UNAVAILABLE, "Synthetic Dom"));
            People.installRemoteAuthority(() -> true);
            assertTrue(ControllerPinManager.verifyLocalDomWorkspace(context, "5729"));
            assertFalse(ControllerPinManager.isSessionUnlocked());
        });
        ActivitySources.install(new ActivityRepository() {
            public Page load(String before, int limit) { return new Page(Collections.emptyList(), null); }
            public Page locate(String key, int limit) { return load(null, limit); }
            public List<ActivityEntry> recent(PersonId person, int limit) { return Collections.emptyList(); }
            public int unreadCount() { return 0; }
            public void markRead(List<String> keys) { }
        });
        RelationshipUi.install(relationships);
    }

    @After public void restore() {
        relationships.failPending();
        if (originalRelationships != null) RelationshipUi.install(originalRelationships);
        if (originalActivity != null) ActivitySources.install(originalActivity);
        for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
            SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
        if (journalLock != null) synchronized (journalLock) {
            if (journalStateExisted) journalStates.put(journalKey, originalJournalState);
            else journalStates.remove(journalKey);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 31)
    public void repeatedRelationshipFailuresKeepLocalCardsThroughPeriodicAndNativeRetry() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CardWitness> witness = new AtomicReference<>();
            await(scenario, activity -> cardsReady(activity) && homePending(activity) && relationships.reads.get() > 0);
            scenario.onActivity(activity -> witness.set(new CardWitness(activity)));
            AtomicBoolean failedOnce = new AtomicBoolean();
            ViewTreeObserver.OnGlobalLayoutListener observer = () -> {
                try {
                    CardWitness current = witness.get();
                    current.assertUnchanged();
                    if (failedOnce.get()) assertErrorOnly(current.activity);
                } catch (AssertionError failure) { observerFailure.compareAndSet(null, failure); }
            };
            scenario.onActivity(activity -> witness.get().body.getViewTreeObserver().addOnGlobalLayoutListener(observer));
            try {
                relationships.failPending();
                await(scenario, activity -> isError(activity) && !homePending(activity));
                failedOnce.set(true);
                scenario.onActivity(activity -> { witness.get().assertUnchanged(); assertErrorOnly(activity); });
                // No presenter invocation, reflection write, or fake clock: the real MainActivity timer starts these reads.
                for (int cycle = 0; cycle < 2; cycle++) {
                    int previous = relationships.reads.get();
                    await(scenario, activity -> relationships.reads.get() > previous && homePending(activity));
                    scenario.onActivity(activity -> { witness.get().assertUnchanged(); assertErrorOnly(activity); });
                    relationships.failPending();
                    await(scenario, activity -> isError(activity) && !homePending(activity));
                    scenario.onActivity(activity -> { witness.get().assertUnchanged(); assertErrorOnly(activity); });
                }
                int previous = relationships.reads.get();
                onView(allOf(withText(R.string.role_retry), isDescendantOfA(sameInstance(witness.get().composition))))
                        .perform(NativeUiActions.revealAboveNavigation(), click());
                await(scenario, activity -> relationships.reads.get() > previous && homePending(activity));
                scenario.onActivity(activity -> { witness.get().assertUnchanged(); assertErrorOnly(activity); });
                relationships.failPending();
                await(scenario, activity -> isError(activity) && !homePending(activity));
                scenario.onActivity(activity -> {
                    witness.get().assertUnchanged(); assertErrorOnly(activity);
                    assertFalse(ControllerPinManager.isSessionUnlocked());
                    assertFalse(ControllerPinManager.canEditConfiguration());
                    assertFalse(LocalSessionControls.end(context, PersonId.LOCAL));
                    assertTrue(ControllerPinManager.isLocalDomWorkspaceUnlocked());
                    assertEquals(0, relationships.operations.get());
                });
                assertNull("Native layout observer detected a detached/replaced/changed local card", observerFailure.get());
            } finally {
                scenario.onActivity(activity -> witness.get().body.getViewTreeObserver().removeOnGlobalLayoutListener(observer));
            }
        }
    }

    private void await(ActivityScenario<MainActivity> scenario, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        AtomicBoolean ready = new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                if (observerFailure.get() != null) throw observerFailure.get();
                ready.set(condition.test(activity));
            });
            if (ready.get()) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Expected actual Home refresh state did not arrive");
    }

    private static boolean homePending(MainActivity activity) {
        try {
            Field presenter = MainActivity.class.getDeclaredField("roleHome"); presenter.setAccessible(true);
            Field data = RoleHomePresenter.class.getDeclaredField("data"); data.setAccessible(true);
            return ((AsyncUiScope) data.get(presenter.get(activity))).isPending("role-home");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static boolean cardsReady(MainActivity activity) {
        View body = activity.findViewById(R.id.home_dom_overview);
        View journal = body.findViewWithTag("home_journal:dom");
        View events = body.findViewWithTag("activity_preview:all");
        View achievements = body.findViewWithTag("achievement_preview");
        return journal != null && events != null && achievements != null
                && !text(journal).contains(activity.getString(R.string.profile_journal_loading))
                && text(events).contains(activity.getString(R.string.dom_activity_empty))
                && achievements.findViewWithTag("achievement_next") != null;
    }

    private static boolean isError(MainActivity activity) {
        return text(activity.findViewById(R.id.home_dom_overview).findViewWithTag("home_people_composition"))
                .contains(activity.getString(R.string.relationships_unavailable));
    }

    private static void assertErrorOnly(MainActivity activity) {
        ViewGroup composition = activity.findViewById(R.id.home_dom_overview).findViewWithTag("home_people_composition");
        assertNotNull(composition);
        assertTrue(isError(activity));
        assertFalse("Completed failure must not reset to Loading on the next tick",
                text(composition).contains(activity.getString(R.string.role_loading)));
        int peerActions = 0;
        for (int index = 0; index < composition.getChildCount(); index++) {
            View slot = composition.getChildAt(index);
            if (slot.findViewWithTag("home_journal:dom") == null) peerActions += clickable(slot);
        }
        assertEquals("Error leaves only Retry in relationship slots, never stale peer controls", 1, peerActions);
        assertFalse(ControllerPinManager.isDomModeActive());
    }

    private static int clickable(View view) {
        int count = view.isClickable() && view.hasOnClickListeners() ? 1 : 0;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            count += clickable(((ViewGroup) view).getChildAt(index));
        return count;
    }

    private static String text(View view) {
        if (view == null) return "";
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) value.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            value.append(text(((ViewGroup) view).getChildAt(index)));
        return value.toString();
    }

    private static final class CardWitness {
        final MainActivity activity;
        final View body, composition;
        final String[] tags = { "home_journal:dom", "activity_preview:all", "achievement_preview" };
        final View[] cards = new View[tags.length];
        final String[] contents = new String[tags.length];
        CardWitness(MainActivity activity) {
            this.activity = activity; body = activity.findViewById(R.id.home_dom_overview);
            composition = body.findViewWithTag("home_people_composition");
            for (int index = 0; index < tags.length; index++) {
                cards[index] = body.findViewWithTag(tags[index]); contents[index] = text(cards[index]);
            }
            assertUnchanged();
        }
        void assertUnchanged() {
            assertSame(composition, body.findViewWithTag("home_people_composition"));
            for (int index = 0; index < tags.length; index++) {
                assertSame(tags[index], cards[index], body.findViewWithTag(tags[index]));
                assertTrue(tags[index], cards[index].isAttachedToWindow() && cards[index].isShown());
                assertFalse(tags[index], contents[index].isEmpty());
                assertEquals(tags[index], contents[index], text(cards[index]));
            }
        }
    }

    private static final class FailingRelationships implements RelationshipUi.Provider {
        final AtomicInteger reads = new AtomicInteger(), operations = new AtomicInteger();
        final List<CompletableFuture<RelationshipUi.Snapshot>> pending = new CopyOnWriteArrayList<>();
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() {
            CompletableFuture<RelationshipUi.Snapshot> result = new CompletableFuture<>();
            pending.add(result); reads.incrementAndGet(); return result;
        }
        void failPending() {
            for (CompletableFuture<RelationshipUi.Snapshot> future : new ArrayList<>(pending))
                if (pending.remove(future)) future.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE));
        }
        private <T> CompletableFuture<T> denied() {
            operations.incrementAndGet(); CompletableFuture<T> result = new CompletableFuture<>();
            result.completeExceptionally(new RelationshipUi.Failure(RelationshipUi.Reason.UNAVAILABLE)); return result;
        }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) { return denied(); }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) { return denied(); }
        public CompletableFuture<RelationshipUi.Operation> resume(String id) { return denied(); }
        public CompletableFuture<RelationshipUi.Operation> cancel(String id) { return denied(); }
    }
}
