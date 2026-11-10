package com.subhub.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.activity.ActivityEntry;
import com.subhub.app.activity.ActivityRepository;
import com.subhub.app.activity.ActivitySources;
import com.subhub.app.people.*;
import com.subhub.app.relationships.DomScopeObserverFixture;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.*;
import org.junit.runner.RunWith;

/** Real page/tab listeners with synthetic identities and no durable Activity feed. */
@RunWith(AndroidJUnit4.class)
public final class SubHubNavigationReselectionAndroidTest {
    @Rule public final org.junit.rules.ExternalResource integration = new org.junit.rules.ExternalResource() {
        private final Map<Field, Object> hooks = new LinkedHashMap<>();
        private boolean originalDom;

        @Override protected void before() throws Throwable {
            originalDom = ControllerPinManager.isDomModeActive();
            for (Field field : People.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                    field.setAccessible(true); hooks.put(field, field.get(null));
                }
            }
            for (String name : List.of(SettingsRepository.PREFERENCES_NAME,
                    "subhub_controller_auth", "subhub_home", "subhub_onboarding"))
                saved.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
            People.installKeyholder(KeyholderPresentationAndroidTest.provider(
                    com.subhub.app.security.KeyholderProvider.State.NONE, ""));
            People.installRemoteAuthority(() -> false);
            People.installRemoteRepository(null);
            People.installLocalProjection(java.util.function.UnaryOperator.identity());
            People.installConnectionUi(null);
        }

        @Override protected void after() {
            // A failed reader drain must also retain the synthetic People hooks and workspace.
            if (!readersDrained) return;
            try {
                for (Map.Entry<Field, Object> entry : hooks.entrySet()) entry.getKey().set(null, entry.getValue());
            } catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            finally {
                if (originalDom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            }
        }
    };
    @Rule public final UiAutomationStateRule automation = new UiAutomationStateRule();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private final Set<AsyncUiScope> readers = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean readersDrained;
    private ActivityRepository previousActivity;
    private DomScopeObserverFixture relationships;
    private final ActivityRepository noActivityFeed = new ActivityRepository() {
        @Override public boolean supports(PersonId person) { return false; }
        @Override public Page load(String before, int limit) { throw unexpected(); }
        @Override public Page locate(String key, int limit) { throw unexpected(); }
        @Override public List<ActivityEntry> recent(PersonId person, int limit) { throw unexpected(); }
        @Override public int unreadCount() { throw unexpected(); }
        @Override public void markRead(List<String> keys) { throw unexpected(); }
        private AssertionError unexpected() { return new AssertionError("Navigation must not query the Activity feed"); }
    };

    @Before public void prepare() throws Exception {
        previousActivity = ActivitySources.repository();
        ActivitySources.install(noActivityFeed);
        relationships = new DomScopeObserverFixture(context);
        for (String name : List.of("subhub_profile_names", "subhub_app_timer_usage"))
            saved.put(name, new LinkedHashMap<>(context.getSharedPreferences(name, 0).getAll()));
        assertTrue(new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        com.subhub.app.onboarding.OnboardingState.complete(context);
        People.installLocalProjection(ignored -> person(PersonId.LOCAL, "Synthetic Sub"));
    }

    @After public void restore() throws Exception {
        assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
        for (AsyncUiScope scope : readers) {
            for (String name : List.of("worker", "serialWorker")) {
                Field field = AsyncUiScope.class.getDeclaredField(name); field.setAccessible(true);
                ExecutorService worker = (ExecutorService) field.get(scope);
                if (worker != null) {
                    assertTrue("Destroyed page shuts down " + name, worker.isShutdown());
                    assertTrue("Destroyed page drains " + name,
                            worker.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        readersDrained = true;
        // Restore canonical hooks only after every owned reader has terminated and deliveries are idle.
        try { if (relationships != null) relationships.close(); }
        finally {
            try {
                for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet())
                    SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue());
                assertSame(noActivityFeed, ActivitySources.repository());
            } finally {
                ActivitySources.install(previousActivity);
                assertSame(previousActivity, ActivitySources.repository());
            }
        }
    }

    @Test public void contractReselectionClosesDetailsAndSurvivesRecreation() throws Exception {
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<ContractActivity> page = ActivityScenario.launch(ContractActivity.class)) {
            ContractActivity activity = host(page);
            await(() -> activity.findViewById(android.R.id.content).findViewWithTag("contract_section:apps") != null);
            onView(withTagValue(is("contract_dom_about_expand"))).perform(NativeUiActions.revealAboveNavigation(), click());
            onView(withTagValue(is("contract_section:apps"))).perform(NativeUiActions.revealAboveNavigation(), click());
            await(() -> activity.findViewById(android.R.id.content).findViewWithTag("contract_details:apps").isShown());
            moveDown(activity);
            onView(withId(R.id.nav_contract)).perform(click());
            await(() -> scroll(activity).getScrollY() == 0);
            page.onActivity(value -> {
                assertEquals(View.GONE, value.findViewById(android.R.id.content)
                        .findViewWithTag("contract_details:apps").getVisibility());
                assertEquals(2, ((android.widget.TextView) value.findViewById(android.R.id.content)
                        .findViewWithTag("contract_dom_about")).getMaxLines());
                assertFalse(ControllerPinManager.isDomModeActive());
            });
            onView(withId(R.id.nav_contract)).perform(click());
            page.recreate();
            ContractActivity recreated = host(page);
            await(() -> recreated.findViewById(android.R.id.content).findViewWithTag("contract_details:apps") != null);
            page.onActivity(value -> {
                assertEquals(0, scroll(value).getScrollY());
                assertEquals(View.GONE, value.findViewById(android.R.id.content)
                        .findViewWithTag("contract_details:apps").getVisibility());
                assertFalse(ControllerPinManager.isDomModeActive());
            });
        }
    }

    @Test public void profileReselectionLeavesTheCatalogAtTheTopInEitherWorkspace() throws Exception {
        for (boolean dom : new boolean[]{true, false}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            assertTrue(ProfileStore.save(context, dom, dom ? "Synthetic Dom" : "Synthetic Sub",
                    "Synthetic reading, running and cooking profile. ".repeat(10), ""));
            Map<String, ?> profiles = new LinkedHashMap<>(context.getSharedPreferences("subhub_profile_names", 0).getAll());
            try (ActivityScenario<ProfileActivity> page = ActivityScenario.launch(ProfileActivity.class)) {
                ProfileActivity activity = host(page);
                onView(withId(R.id.stats_section_achievements)).perform(NativeUiActions.revealAboveNavigation(), click());
                moveDown(activity);
                onView(withId(R.id.nav_stats)).perform(click());
                await(() -> scroll(activity).getScrollY() == 0);
                page.onActivity(value -> {
                    assertTrue(value.findViewById(R.id.stats_section_profile).isSelected());
                    assertFalse(value.findViewById(R.id.stats_section_achievements).isSelected());
                    assertEquals(dom, ControllerPinManager.isDomModeActive());
                });
                moveDown(activity);
                onView(withId(R.id.nav_stats)).perform(click());
                await(() -> scroll(activity).getScrollY() == 0);
                page.recreate(); host(page);
                page.onActivity(value -> {
                    assertEquals(0, scroll(value).getScrollY());
                    assertTrue(value.findViewById(R.id.stats_section_profile).isSelected());
                    assertEquals(dom, ControllerPinManager.isDomModeActive());
                });
                assertEquals(profiles, context.getSharedPreferences("subhub_profile_names", 0).getAll());
            }
        }
    }

    @Test public void overviewReselectionScrollsTheExistingHostWithoutChangingWorkspace() throws Exception {
        saved.put(FavoriteSubStore.PREFERENCES_NAME,
                new LinkedHashMap<>(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).getAll()));
        Map<PersonId, PersonSnapshot> roster = new LinkedHashMap<>();
        List<com.subhub.app.relationships.RelationshipUi.Edge> edges = new ArrayList<>();
        String[] names = {"Morgan", "Sage", "River", "Robin"};
        for (int index = 0; index < names.length; index++) {
            com.subhub.app.relationships.RelationshipUi.Edge edge =
                    DomScopeObserverFixture.peer("overview" + index, "navbar", index + 1);
            edges.add(edge);
            roster.put(edge.personId, overviewPerson(person(edge.personId, names[index]), List.of(), index + 1));
        }
        People.installRemoteRepository(new PersonRepository() {
            @Override public List<PersonId> people() { return new ArrayList<>(roster.keySet()); }
            @Override public PersonSnapshot load(PersonId id) { return Objects.requireNonNull(roster.get(id)); }
        });
        relationships.outgoing(edges.toArray(new com.subhub.app.relationships.RelationshipUi.Edge[0]));
        PersonSnapshot favorite = roster.get(edges.get(0).personId);
        FavoriteSubStore favorites = new FavoriteSubStore(context);
        if (!favorites.isFavorite(favorite)) assertTrue(favorites.toggle(favorite));
        List<PersonSnapshot.Allowance> allowances = new ArrayList<>();
        String[] apps = {"Reading", "Calendar", "Mail", "Maps", "Music", "Notes",
                "Podcasts", "Recipes", "Training", "Weather", "Browser", "Settings"};
        for (int index = 0; index < apps.length; index++)
            allowances.add(new PersonSnapshot.Allowance("fixture.app" + index, apps[index],
                    (index + 1) * 60_000L, 20 + index * 5));
        PersonSnapshot local = overviewPerson(person(PersonId.LOCAL, "Synthetic Sub"), allowances, 0);
        People.installLocalProjection(ignored -> local);
        for (boolean dom : new boolean[]{true, false}) {
            if (dom) ControllerPinManager.enterDomMode(); else ControllerPinManager.enterSubMode();
            try (ActivityScenario<MainActivity> page = ActivityScenario.launch(MainActivity.class)) {
                MainActivity activity = host(page);
                if (dom) {
                    await(() -> activity.findViewById(android.R.id.content)
                            .findViewWithTag("home_recent_person:" + edges.get(3).personId.value()) != null);
                    page.onActivity(value -> assertNotNull(value.findViewById(android.R.id.content)
                            .findViewWithTag("home_favorite:" + favorite.id.value())));
                } else {
                    await(() -> activity.findViewById(android.R.id.content).findViewWithTag("home_usage_all") != null);
                    onView(withTagValue(is("home_usage_all"))).perform(NativeUiActions.revealAboveNavigation(), click());
                    await(() -> activity.findViewById(android.R.id.content).findViewWithTag("home_app_icon:fixture.app11") != null);
                }
                moveDown(activity);
                onView(withId(R.id.nav_home)).perform(click());
                await(() -> scroll(activity).getScrollY() == 0);
                onView(withId(R.id.nav_home)).perform(click());
                page.onActivity(value -> {
                    assertSame(activity, value);
                    assertEquals(0, scroll(value).getScrollY());
                    assertEquals(dom, ControllerPinManager.isDomModeActive());
                });
            }
        }
    }

    private static PersonSnapshot overviewPerson(PersonSnapshot source, List<PersonSnapshot.Allowance> allowances,
            long additionOrder) {
        return new PersonSnapshot(source.id, source.name, source.device, source.contractName, source.serviceLabel,
                source.connection, source.freshness, source.lastSyncedAt, source.capabilities, source.sections,
                allowances, source.currency, source.protectedSeconds, source.activityEvents, source.tributeCents,
                source.sessions, source.serviceActive, source.sync, source.sessionIdentity, additionOrder)
                .withProfile(source.profile, source.featureConsent).withContractDom(source.contractDom);
    }

    @Test public void peopleReselectionResetsTheNestedRosterWithoutChangingFavorites() throws Exception {
        ControllerPinManager.enterDomMode();
        Map<PersonId, PersonSnapshot> roster = new LinkedHashMap<>();
        List<com.subhub.app.relationships.RelationshipUi.Edge> edges = new ArrayList<>();
        String[] names = {"Morgan", "Sage", "River", "Robin", "Avery", "Quinn", "Rowan", "Alex"};
        for (int index = 0; index < names.length; index++) {
            com.subhub.app.relationships.RelationshipUi.Edge edge =
                    DomScopeObserverFixture.peer("navbar" + index, "roster", index + 1);
            edges.add(edge);
            roster.put(edge.personId, person(edge.personId, names[index]));
        }
        People.installRemoteRepository(new PersonRepository() {
            @Override public List<PersonId> people() { return new ArrayList<>(roster.keySet()); }
            @Override public PersonSnapshot load(PersonId id) { return Objects.requireNonNull(roster.get(id)); }
        });
        relationships.outgoing(edges.toArray(new com.subhub.app.relationships.RelationshipUi.Edge[0]));
        Map<String, ?> favorites = new LinkedHashMap<>(context.getSharedPreferences("subhub_home", 0).getAll());
        try (ActivityScenario<PeopleActivity> page = ActivityScenario.launch(PeopleActivity.class)) {
            PeopleActivity activity = host(page);
            await(() -> activity.findViewById(android.R.id.content).findViewWithTag("profile_preview:sub") != null);
            moveDown(activity);
            onView(withId(R.id.nav_people)).perform(click());
            await(() -> ((ScrollView) activity.findViewById(R.id.people_roster_scroll)).getScrollY() == 0);
            onView(withId(R.id.nav_people)).perform(click());
            page.recreate(); host(page);
            page.onActivity(value -> {
                assertEquals(0, ((ScrollView) value.findViewById(R.id.people_roster_scroll)).getScrollY());
                assertTrue(ControllerPinManager.isDomModeActive());
            });
            assertEquals(favorites, context.getSharedPreferences("subhub_home", 0).getAll());
        }
    }

    private <T extends Activity> T host(ActivityScenario<T> page) {
        java.util.concurrent.atomic.AtomicReference<T> host = new java.util.concurrent.atomic.AtomicReference<>();
        page.onActivity(activity -> {
            host.set(activity);
            readers.add(AsyncUiScope.forPage((androidx.lifecycle.LifecycleOwner) activity));
        });
        return host.get();
    }

    private static void moveDown(Activity activity) throws Exception {
        await(() -> {
            ScrollView scroll = scroll(activity);
            return scroll != null && scroll.getHeight() > 0 && scroll.getChildCount() > 0
                    && scroll.getChildAt(0).getHeight() > scroll.getHeight()
                            - scroll.getPaddingTop() - scroll.getPaddingBottom();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ScrollView scroll = scroll(activity);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            assertTrue("Fixture must actually be scrolled", scroll.getScrollY() > 0);
        });
    }

    private static ScrollView scroll(Activity activity) {
        return findScroll(activity.findViewById(android.R.id.content));
    }

    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            ScrollView found = findScroll(((ViewGroup) view).getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(20);
        }
        throw new AssertionError("Native navigation state did not settle");
    }

    private static PersonSnapshot person(PersonId id, String name) {
        String bio = "Synthetic reading, running and cooking profile. ".repeat(10);
        return new PersonSnapshot(id, name, "Synthetic device", "Synthetic contract", "1 hour",
                id.isLocal() ? PersonSnapshot.Connection.LOCAL : PersonSnapshot.Connection.OFFLINE,
                PersonSnapshot.Freshness.CURRENT, 0,
                EnumSet.of(PersonSnapshot.Capability.VIEW_CONTRACT, PersonSnapshot.Capability.VIEW_STATS),
                List.of(new PersonSnapshot.Section("apps", "Selected apps", "2 apps",
                                "Chrome\nSettings\n" + "Synthetic application details\n".repeat(30)),
                        new PersonSnapshot.Section("duration", "Service duration", "1 hour", "Synthetic duration details")),
                List.of(), "USD", 0, 0, 0, 0, false)
                .withProfile(new ProfileData(name, bio, ""), Map.of())
                .withContractDom(new ProfileData("Synthetic Dom", bio, ""));
    }
}
