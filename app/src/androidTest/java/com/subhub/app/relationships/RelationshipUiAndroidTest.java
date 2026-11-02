package com.subhub.app.relationships;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withTagValue;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.subhub.app.NativeUiActions.revealAboveNavigation;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.onboarding.OnboardingActivity;
import com.subhub.app.onboarding.OnboardingFlow;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.people.FavoriteSubStore;
import com.subhub.app.people.People;
import com.subhub.app.people.PeopleActivity;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonRepository;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Provider fixtures qualify presentation only; they create no production relationship or authority. */
public final class RelationshipUiAndroidTest {
    @Rule public final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String STORE = "subhub_relationship_presentation";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private RelationshipUi.Provider beforeProvider;
    private Map<String, ?> beforeJournal, beforeFavorites;
    private String beforeAccessibility, beforeServices;

    @Before public void prepare() throws Exception {
        beforeProvider = RelationshipUi.provider();
        beforeJournal = new LinkedHashMap<>(context.getSharedPreferences(STORE, 0).getAll());
        beforeFavorites = new LinkedHashMap<>(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).getAll());
        assertTrue(context.getSharedPreferences(STORE, 0).edit().clear().commit());
        clearJournalCache();
        assertTrue(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0).edit().clear().commit());
        beforeAccessibility = Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled");
        beforeServices = Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services");
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.BOTH.name()).commit());
        assertTrue(ControllerPinManager.setPin(context, "2468")); ControllerPinManager.enterDomMode();
        OnboardingState.complete(context);
        RelationshipUi.install(RelationshipUi.unavailable());
    }

    @After public void restore() throws Exception {
        RelationshipUi.install(beforeProvider);
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(STORE, 0), beforeJournal);
        clearJournalCache();
        SharedPreferenceTestRestore.restore(context.getSharedPreferences(FavoriteSubStore.PREFERENCES_NAME, 0), beforeFavorites);
        assertEquals(beforeAccessibility, Settings.Secure.getString(context.getContentResolver(), "accessibility_enabled"));
        assertEquals(beforeServices, Settings.Secure.getString(context.getContentResolver(), "enabled_accessibility_services"));
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void defaultCompatibilityIsNotRestoredAfterAnAuthoritativeFailure() throws Exception {
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = host(scenario);
            await(() -> tagged(host.get(), "person:local") != null);
            Fixture provider = new Fixture(); RelationshipUi.install(provider);
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> textExists(host.get(), R.string.relationships_no_subs));
            scenario.onActivity(a -> assertNull(tagged(a, "person:local")));
            provider.snapshot = failed();
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> {
                TextView guidance = findText(host.get().findViewById(android.R.id.content),
                        host.get().getString(R.string.home_favorite_gesture));
                return tagged(host.get(), "relationship_unavailable") != null
                        && guidance != null && guidance.getVisibility() == View.GONE;
            });
            scenario.onActivity(a -> {
                assertNull(tagged(a, "person:local"));
                assertEquals(1, countText(a.findViewById(android.R.id.content), a.getString(R.string.relationships_retry)));
                TextView favorite = findText(a.findViewById(android.R.id.content), a.getString(R.string.home_favorite_gesture));
                assertNotNull(favorite); assertEquals(View.GONE, favorite.getVisibility());
                assertFalse(textExists(a, R.string.role_person_unavailable));
            });
            RelationshipUi.install(RelationshipUi.unavailable());
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> {
                TextView guidance = findText(host.get().findViewById(android.R.id.content),
                        host.get().getString(R.string.home_favorite_gesture));
                return tagged(host.get(), "relationship_unavailable") != null
                        && guidance != null && guidance.getVisibility() == View.GONE;
            });
            scenario.onActivity(a -> assertNull(tagged(a, "person:local")));
            onView(tag("relationship_unavailable")).perform(revealAboveNavigation());
            capture(host.get(), "authoritative-unavailable", "relationship_unavailable");
            PersonSnapshot recovered = person("fixture-recovered", "Recovered Sub", PersonSnapshot.Connection.OFFLINE);
            AtomicBoolean personAvailable = new AtomicBoolean(true);
            People.installRemoteRepository(new PersonRepository() {
                public List<PersonId> people() { return Collections.singletonList(recovered.id); }
                public PersonSnapshot load(PersonId id) {
                    if (!personAvailable.get()) throw new IllegalStateException("Fixture person unavailable");
                    assertEquals(recovered.id, id); return recovered;
                }
            });
            provider.snapshot = CompletableFuture.completedFuture(snapshot(Collections.singletonList(
                    edge(recovered.id, 10, RelationshipUi.Availability.OFFLINE)), participants()));
            RelationshipUi.install(provider);
            onView(tag("relationship_refresh")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "person:" + recovered.id.value()) != null
                    && tagged(host.get(), "relationship_prepare") != null);
            scenario.onActivity(a -> {
                assertNull(tagged(a, "relationship_unavailable"));
                assertFalse(textExists(a, R.string.role_person_unavailable));
                assertNotNull(tagged(a, "relationship_prepare"));
            });
            onView(tag("roster:" + recovered.id.value() + "_name")).perform(revealAboveNavigation());
            capture(host.get(), "relationship-recovered-roster", "roster:" + recovered.id.value() + "_name");
            personAvailable.set(false); scenario.recreate(); scenario.onActivity(host::set);
            await(() -> textExists(host.get(), R.string.role_person_unavailable));
            scenario.onActivity(a -> {
                assertNull(tagged(a, "relationship_unavailable"));
                assertEquals(1, countText(a.findViewById(android.R.id.content), a.getString(R.string.role_retry)));
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void formalRosterKeepsOfflineOrderAndFavoriteStyling() throws Exception {
        PersonSnapshot first = person("fixture-first", "Alexandra Morgan with a long display name", PersonSnapshot.Connection.OFFLINE);
        PersonSnapshot second = person("fixture-second", "Sam Taylor", PersonSnapshot.Connection.CONNECTED);
        People.installRemoteRepository(new PersonRepository() {
            public List<PersonId> people() { return Arrays.asList(second.id, first.id); }
            public PersonSnapshot load(PersonId id) {
                if (first.id.equals(id)) return first;
                if (second.id.equals(id)) return second;
                throw new IllegalArgumentException("Unexpected fixture person");
            }
        });
        Fixture provider = new Fixture();
        provider.snapshot = CompletableFuture.completedFuture(snapshot(Arrays.asList(
                edge(first.id, 10, RelationshipUi.Availability.OFFLINE),
                edge(second.id, 20, RelationshipUi.Availability.AVAILABLE)), participants()));
        RelationshipUi.install(provider);
        try (ActivityScenario<PeopleActivity> scenario = ActivityScenario.launch(PeopleActivity.class)) {
            AtomicReference<PeopleActivity> host = host(scenario);
            await(() -> tagged(host.get(), "person:" + second.id.value()) != null);
            scenario.onActivity(a -> {
                View firstCard = tagged(a, "person:" + first.id.value()), secondCard = tagged(a, "person:" + second.id.value());
                assertSame(firstCard.getParent(), secondCard.getParent());
                assertTrue(((ViewGroup) firstCard.getParent()).indexOfChild(firstCard)
                        < ((ViewGroup) secondCard.getParent()).indexOfChild(secondCard));
                assertNull(tagged(a, "person:local"));
            });
            onView(tag("roster:" + first.id.value() + "_name")).perform(revealAboveNavigation(), longClick());
            await(() -> tagged(host.get(), "people_favorite:" + first.id.value()) != null);
            assertEquals(FavoriteSubStore.identity(first.id), new FavoriteSubStore(context).identity());
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "people_favorite:" + first.id.value()) != null);
            onView(tag("people_favorite:" + first.id.value())).perform(revealAboveNavigation());
            capture(host.get(), "formal-offline-favorite", "people_favorite:" + first.id.value());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void recreatedReviewResumesPreparedStatusAndCancelsWithoutConfirming() throws Exception {
        Fixture provider = new Fixture(); RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "relationship_prepare") != null);
            onView(tag("relationship_prepare")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "relationship_confirm") != null);
            onView(tag("relationship_confirm")).perform(revealAboveNavigation());
            capture(host.get(), "local-review", "relationship_confirm");
            assertEquals(1, provider.preparations.size()); assertTrue(provider.confirmations.isEmpty());
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "relationship_resume") != null);
            scenario.onActivity(a -> assertNull(tagged(a, "relationship_confirm")));
            onView(tag("relationship_resume")).perform(revealAboveNavigation(), click());
            await(() -> textExists(host.get(), R.string.relationships_prepared));
            assertEquals(Collections.singletonList("operation"), provider.resumes);
            assertEquals(provider.deadline, provider.resumedDeadline);
            scenario.onActivity(a -> assertNull(tagged(a, "relationship_confirm")));
            onView(tag("relationship_cancel")).perform(revealAboveNavigation());
            capture(host.get(), "recreated-prepared", "relationship_cancel");
            onView(tag("relationship_cancel")).perform(click());
            await(() -> textExists(host.get(), R.string.relationships_cancelled));
            assertEquals(Collections.singletonList("operation"), provider.cancellations);
            assertTrue(provider.confirmations.isEmpty()); assertEquals(1, provider.preparations.size());
            assertNull(RelationshipPanel.journal(context).read());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void explicitScopeConfirmationUsesCredentialAndExactOperation() throws Exception {
        Fixture provider = new Fixture(); RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "relationship_prepare") != null);
            onView(tag("relationship_prepare")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "relationship_confirm") != null);
            onView(tag("relationship_confirm")).perform(revealAboveNavigation(), click());
            onView(withHint(R.string.controller_pin_label)).perform(replaceText("2468"), closeSoftKeyboard());
            onView(withText(R.string.controller_pin_unlock)).perform(click());
            await(() -> textExists(host.get(), R.string.relationships_pending));
            assertEquals(Collections.singletonList(Arrays.asList("operation", DIGEST)), provider.confirmations);
            assertEquals(1, provider.preparations.size());
            assertEquals("operation", RelationshipPanel.journal(context).read().operationId);
            onView(tag("relationship_operation_status")).perform(revealAboveNavigation());
            capture(host.get(), "explicit-confirm-pending", "relationship_operation_status");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void nextBackAndLatePreparationRetainOriginalRequestWithoutActivation() throws Exception {
        Fixture provider = new Fixture(); provider.preparation = new CompletableFuture<>();
        RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "relationship_prepare") != null);
            onView(tag("relationship_prepare")).perform(revealAboveNavigation(), click());
            await(() -> provider.preparations.size() == 1);
            List<String> original = provider.preparations.get(0);
            onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.tour_next)).perform(click());
            await(() -> tagged(host.get(), "onboarding_page:sub_tasks") != null);
            provider.preparation.complete(provider.review("dom", "sub"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> assertNull(tagged(a, "relationship_confirm")));
            assertTrue(RelationshipPanel.journal(context).read().unresolved());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> host.get().getOnBackPressedDispatcher().onBackPressed());
            await(() -> tagged(host.get(), "relationship_retry_prepare") != null);
            provider.preparation = null;
            onView(tag("relationship_retry_prepare")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "relationship_confirm") != null);
            assertEquals(original, provider.preparations.get(1));
            assertTrue(provider.confirmations.isEmpty()); assertTrue(provider.cancellations.isEmpty());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void participantSpinnersUseNativeSelectionAndPreserveChosenRefs() throws Exception {
        Fixture provider = new Fixture();
        provider.snapshot = CompletableFuture.completedFuture(snapshot(Collections.emptyList(), Arrays.asList(
                participant("dom", RelationshipUi.Role.DOM, "First Dom"),
                participant("dom-two", RelationshipUi.Role.DOM, "Second Dom"),
                participant("sub", RelationshipUi.Role.SUB, "First Sub"),
                participant("sub-two", RelationshipUi.Role.SUB, "Second Sub"))));
        RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "relationship_dom_choice") != null);
            onView(tag("relationship_dom_choice")).perform(revealAboveNavigation(), click());
            onView(withText("Second Dom")).perform(click());
            onView(tag("relationship_sub_choice")).perform(revealAboveNavigation(), click());
            onView(withText("Second Sub")).perform(click());
            onView(tag("relationship_prepare")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "relationship_confirm") != null);
            assertEquals("dom-two", provider.preparations.get(0).get(1));
            assertEquals("sub-two", provider.preparations.get(0).get(2));
            assertEquals("operation", RelationshipPanel.journal(context).read().operationId);
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void subOnlyPresentationDoesNotInventALocalDom() throws Exception {
        assertTrue(new SettingsRepository(context).preferences().edit()
                .putString(DeviceRolePreferences.KEY_MODE, DeviceRolePreferences.Mode.SUB_ONLY.name()).commit());
        ControllerPinManager.enterSubMode();
        Fixture provider = new Fixture(); provider.snapshot = CompletableFuture.completedFuture(snapshot(Collections.emptyList(),
                Collections.singletonList(participant("sub", RelationshipUi.Role.SUB, "Local Sub"))));
        RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "onboarding_page:sub_connect_dom") != null);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a -> {
                assertNull(tagged(a, "relationship_prepare")); assertNull(tagged(a, "relationship_dom_profile"));
                assertNotNull(tagged(a, "onboarding_action:sub_connect_dom"));
            });
            assertTrue(provider.preparations.isEmpty()); assertTrue(provider.confirmations.isEmpty());
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void authorizedPendingSummaryResumesWithoutCreatingAnotherRequest() throws Exception {
        Fixture provider = new Fixture();
        provider.snapshot = CompletableFuture.completedFuture(new RelationshipUi.Snapshot(true, "revision", participants(),
                Collections.emptyList(), null, Collections.singletonList(new RelationshipUi.Operation("operation", DIGEST,
                        RelationshipUi.OperationState.PENDING, provider.deadline, null, null, null))));
        RelationshipUi.install(provider); tour();
        try (ActivityScenario<OnboardingActivity> scenario = ActivityScenario.launch(OnboardingActivity.class)) {
            AtomicReference<OnboardingActivity> host = host(scenario);
            await(() -> tagged(host.get(), "relationship_resume_summary") != null);
            assertTrue(provider.preparations.isEmpty()); assertTrue(provider.resumes.isEmpty());
            scenario.onActivity(a -> assertNull(tagged(a, "relationship_prepare")));
            onView(tag("relationship_resume_summary")).perform(revealAboveNavigation(), click());
            await(() -> tagged(host.get(), "relationship_resume") != null);
            assertEquals(Collections.singletonList("operation"), provider.resumes);
            assertTrue(provider.preparations.isEmpty()); assertTrue(provider.confirmations.isEmpty());
            scenario.onActivity(a -> assertNull(tagged(a, "relationship_confirm")));
            onView(tag("relationship_cancel")).perform(revealAboveNavigation());
            capture(host.get(), "server-prepared-summary", "relationship_cancel");
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void formalHomeFailureHidesImplicitRelationshipsAndPreservesStandaloneSubData() throws Exception {
        Fixture provider = new Fixture(); RelationshipUi.install(provider);
        android.content.Intent intent = new android.content.Intent(context, MainActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_SUPPRESS_PERMISSION_READINESS, true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = host(scenario);
            await(() -> tagged(host.get(), "home_devices") != null);
            scenario.onActivity(a -> {
                assertTrue(textExists(tagged(a, "home_devices"), "0"));
                assertNull(tagged(a, "home_favorite:local"));
            });
            provider.snapshot = failed(); scenario.recreate(); scenario.onActivity(host::set);
            await(() -> textExists(host.get(), R.string.relationships_unavailable));
            scenario.onActivity(a -> {
                assertNull(tagged(a, "home_favorite:local")); assertNull(tagged(a, "home_devices"));
                findText(a.findViewById(android.R.id.content), a.getString(R.string.relationships_unavailable))
                        .setTag("relationship_home_dom_error");
            });
            onView(tag("relationship_home_dom_error")).perform(revealAboveNavigation());
            capture(host.get(), "formal-home-dom-error", "relationship_home_dom_error");
        }
        ControllerPinManager.enterSubMode();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            AtomicReference<MainActivity> host = host(scenario);
            await(() -> tagged(host.get(), "home_tasks") != null && textExists(host.get(), R.string.relationships_unavailable));
            scenario.onActivity(a -> {
                assertEquals(View.VISIBLE, a.findViewById(R.id.home_sub_summary).getVisibility());
                assertEquals(View.VISIBLE, a.findViewById(R.id.home_service_widget).getVisibility());
                findText(a.findViewById(android.R.id.content), a.getString(R.string.relationships_unavailable))
                        .setTag("relationship_home_sub_error");
            });
            onView(tag("relationship_home_sub_error")).perform(revealAboveNavigation());
            capture(host.get(), "formal-home-sub-standalone", "relationship_home_sub_error");
            ProfileData legacy = new ProfileData("Unrelated legacy Dom", "Unrelated legacy biography", "");
            People.installLocalProjection(person -> person.withContractDom(legacy, new PersonId("legacy-dom")));
            RelationshipUi.Edge governing = new RelationshipUi.Edge("new-relationship", "new-generation", "new-dom", "sub",
                    RelationshipUi.Delivery.PEER, "ACTIVE", 1, PersonId.LOCAL,
                    RelationshipUi.Availability.UNKNOWN, PersonSnapshot.Freshness.UNKNOWN);
            provider.snapshot = CompletableFuture.completedFuture(new RelationshipUi.Snapshot(true, "new-revision",
                    Collections.singletonList(participant("sub", RelationshipUi.Role.SUB, "Local Sub")),
                    Collections.emptyList(), governing, Collections.emptyList()));
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> tagged(host.get(), "home_contract_dom_name") != null);
            scenario.onActivity(a -> {
                assertEquals(a.getString(R.string.role_your_dom), ((TextView) tagged(a, "home_contract_dom_name")).getText().toString());
                assertFalse(textExists(a, legacy.name)); assertFalse(textExists(a, legacy.about));
                assertNull(tagged(a, "home_dom_about"));
            });
            onView(tag("home_contract_dom_name")).perform(revealAboveNavigation());
            capture(host.get(), "formal-dom-unknown-not-legacy", "home_contract_dom_name");
            ProfileData authorized = new ProfileData("Current authorized Dom", "Current authorized biography", "");
            provider.snapshot = CompletableFuture.completedFuture(new RelationshipUi.Snapshot(true, "matched-revision",
                    Arrays.asList(new RelationshipUi.Participant("new-dom", RelationshipUi.Role.DOM, authorized),
                            participant("sub", RelationshipUi.Role.SUB, "Local Sub")), Collections.emptyList(), governing,
                    Collections.emptyList()));
            scenario.recreate(); scenario.onActivity(host::set);
            await(() -> textExists(host.get(), authorized.name));
            scenario.onActivity(a -> {
                assertEquals(authorized.about, ((TextView) tagged(a, "home_dom_about")).getText().toString());
                assertFalse(textExists(a, legacy.name)); assertFalse(textExists(a, legacy.about));
            });
            onView(tag("home_contract_dom_name")).perform(revealAboveNavigation());
            capture(host.get(), "formal-dom-authorized-match", "home_contract_dom_name");
        }
        assertTrue(provider.preparations.isEmpty()); assertTrue(provider.confirmations.isEmpty());
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    public void actualPreferencesJournalFencesSyntheticMemoryAdvancedFailuresAndReopens() throws Exception {
        SharedPreferences backing = context.getSharedPreferences(STORE, 0);
        MemoryAdvancePreferences front = new MemoryAdvancePreferences(backing);
        Context wrapped = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return STORE.equals(name) ? front : super.getSharedPreferences(name, mode);
            }
        };
        Fixture provider = new Fixture();
        RelationshipPresentation.Journal journal = RelationshipPanel.journal(wrapped);
        front.failures = 2;
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> RelationshipPresentation.access(provider, journal).join());
        assertTrue(front.getBoolean("formal_seen", false));
        assertFalse(backing.getBoolean("formal_seen", false));
        RelationshipPresentation.Journal reopened = RelationshipPanel.journal(wrapped);
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> RelationshipPresentation.access(provider, reopened).join());
        assertEquals(0, provider.snapshotCalls.get());
        assertFalse(backing.getBoolean("formal_seen", false));
        assertTrue(RelationshipPresentation.access(provider, reopened).join().snapshot.supported);
        assertEquals(1, provider.snapshotCalls.get());
        assertTrue(backing.getBoolean("formal_seen", false));
        RelationshipPresentation.Saved original = RelationshipPresentation.Saved.preparing(
                "original-key", "dom", "sub", "original-revision");
        front.failures = 1;
        assertFalse(reopened.replace(null, original));
        assertNull(backing.getString("operation", null));
        assertTrue(front.getString("operation", "").contains("original-key"));
        RelationshipPresentation.Journal retryJournal = RelationshipPanel.journal(wrapped);
        assertEquals(original, retryJournal.read());
        RelationshipPresentation model = new RelationshipPresentation(() -> provider, retryJournal, Runnable::run,
                () -> "synthetic-native-journal", () -> true, ignored -> { });
        model.refresh();
        assertEquals("original-key", new JSONObject(backing.getString("operation", "")).getString("key"));
        provider.onPrepare = () -> front.failures = 1;
        model.prepare();
        assertEquals(RelationshipPresentation.Mode.UNAVAILABLE, model.mode());
        assertEquals("operation", new JSONObject(front.getString("operation", "")).getString("id"));
        assertEquals("original-key", new JSONObject(backing.getString("operation", "")).getString("key"));
        assertEquals(original, RelationshipPanel.journal(wrapped).read());
        provider.onPrepare = null; model.detach();
        RelationshipPresentation.Journal recoveredJournal = RelationshipPanel.journal(wrapped);
        RelationshipPresentation recovered = new RelationshipPresentation(() -> provider, recoveredJournal, Runnable::run,
                () -> "synthetic-native-journal", () -> true, ignored -> { });
        recovered.refresh(); recovered.prepare();
        assertEquals(2, provider.preparations.size());
        assertEquals(provider.preparations.get(0), provider.preparations.get(1));
        assertEquals(Arrays.asList("original-key", "dom", "sub", "original-revision"), provider.preparations.get(1));
        RelationshipPresentation.Saved operation = recoveredJournal.read();
        assertEquals("operation", operation.operationId); assertNull(operation.operationKey);
        assertEquals(2, new JSONObject(backing.getString("operation", "")).length());
        front.failures = 1;
        assertFalse(recoveredJournal.replace(operation, null));
        assertNull(front.getString("operation", null));
        assertEquals(operation, RelationshipPanel.journal(wrapped).read());
        assertEquals("operation", new JSONObject(backing.getString("operation", "")).getString("id"));
        recovered.refresh(); front.failures = 1; recovered.resume();
        assertTrue(provider.resumes.isEmpty());
        recovered.refresh(); recovered.resume();
        assertEquals(Collections.singletonList("operation"), provider.resumes);
        recovered.cancel();
        assertEquals(Collections.singletonList("operation"), provider.cancellations);
        assertNull(recoveredJournal.read()); assertNull(backing.getString("operation", null));
        assertTrue(provider.confirmations.isEmpty());
        recovered.detach(); clearJournalCache();
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> RelationshipPresentation.access(RelationshipUi.unavailable(), RelationshipPanel.journal(context)).join());
        File directory = new File(context.getFilesDir(), "relationship-journal");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject proof = new JSONObject().put("scope", "Synthetic memory-advanced failed commits over real Android preferences; not an OS filesystem failure")
                .put("realProviderCallsBlockedUntilSuccessfulLatch", true).put("newHelperOriginalKeyPreserved", true)
                .put("promotionAndClearAcknowledged", true).put("preparationCalls", provider.preparations.size())
                .put("resumes", provider.resumes.size()).put("cancels", provider.cancellations.size());
        Files.write(new File(directory, "writer-proof.json").toPath(), proof.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void tour() {
        assertTrue(context.getSharedPreferences("subhub_onboarding", 0).edit().putBoolean("completed", false)
                .putBoolean("in_progress", true).putInt("draft_flow_version", OnboardingFlow.VERSION)
                .putString("draft_page", "sub_connect_dom").putString("draft_role", DeviceRolePreferences.get(context).name())
                .putBoolean("draft_role_chosen", true).commit());
    }

    private static PersonSnapshot person(String id, String name, PersonSnapshot.Connection connection) {
        return new PersonSnapshot(new PersonId(id), name, "", "", "", connection, PersonSnapshot.Freshness.CURRENT, 0,
                EnumSet.of(PersonSnapshot.Capability.VIEW_STATS), Collections.emptyList(), Collections.emptyList(),
                "EUR", 0, 0, 0, 0, false).withProfile(ProfileData.empty(name), Collections.emptyMap());
    }
    private static RelationshipUi.Edge edge(PersonId person, long order, RelationshipUi.Availability availability) {
        return new RelationshipUi.Edge("relationship-" + person.value(), "generation", "dom", "sub-" + person.value(),
                RelationshipUi.Delivery.PEER, "ACTIVE", order, person, availability, PersonSnapshot.Freshness.CURRENT);
    }
    private static List<RelationshipUi.Participant> participants() {
        return Arrays.asList(participant("dom", RelationshipUi.Role.DOM, "Alexandra Morgan"),
                participant("sub", RelationshipUi.Role.SUB, "Sam Taylor"));
    }
    private static RelationshipUi.Participant participant(String ref, RelationshipUi.Role role, String name) {
        return new RelationshipUi.Participant(ref, role, ProfileData.empty(name));
    }
    private static RelationshipUi.Snapshot snapshot(List<RelationshipUi.Edge> edges, List<RelationshipUi.Participant> participants) {
        return new RelationshipUi.Snapshot(true, "revision", participants, edges, null, Collections.emptyList());
    }
    private static <T> CompletableFuture<T> failed() {
        CompletableFuture<T> future = new CompletableFuture<>(); future.completeExceptionally(new IllegalStateException("Unavailable"));
        return future;
    }
    private static final class Fixture implements RelationshipUi.Provider {
        volatile CompletableFuture<RelationshipUi.Snapshot> snapshot = CompletableFuture.completedFuture(
                RelationshipUiAndroidTest.snapshot(Collections.emptyList(), participants()));
        volatile CompletableFuture<RelationshipUi.LocalReview> preparation;
        final long deadline = System.currentTimeMillis() + 600_000;
        volatile long resumedDeadline;
        final List<List<String>> preparations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<List<String>> confirmations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> resumes = new java.util.concurrent.CopyOnWriteArrayList<>(), cancellations = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger snapshotCalls = new java.util.concurrent.atomic.AtomicInteger();
        volatile Runnable onPrepare;
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() { snapshotCalls.incrementAndGet(); return snapshot; }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) {
            preparations.add(Arrays.asList(key, dom, sub, revision));
            if (onPrepare != null) onPrepare.run();
            return preparation == null ? CompletableFuture.completedFuture(review(dom, sub)) : preparation;
        }
        RelationshipUi.LocalReview review(String dom, String sub) {
            return new RelationshipUi.LocalReview("operation", DIGEST, dom, sub, DIGEST, deadline,
                    "Censor and Limits\nTasks can be assigned and reviewed.");
        }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) {
            confirmations.add(Arrays.asList(id, digest));
            return CompletableFuture.completedFuture(new RelationshipUi.Operation(id, digest,
                    RelationshipUi.OperationState.PENDING, deadline, null, null, null));
        }
        public CompletableFuture<RelationshipUi.Operation> resume(String id) {
            resumes.add(id); resumedDeadline = deadline;
            return CompletableFuture.completedFuture(new RelationshipUi.Operation(id, DIGEST,
                    RelationshipUi.OperationState.PREPARED, deadline, null, null, null));
        }
        public CompletableFuture<RelationshipUi.Operation> cancel(String id) {
            cancellations.add(id);
            return CompletableFuture.completedFuture(new RelationshipUi.Operation(id, DIGEST,
                    RelationshipUi.OperationState.CANCELLED, deadline, null, null, RelationshipUi.Reason.CANCELLED));
        }
    }

    private static void clearJournalCache() throws Exception {
        java.lang.reflect.Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK"); lock.setAccessible(true);
        java.lang.reflect.Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES"); states.setAccessible(true);
        synchronized (lock.get(null)) { ((Map<?, ?>) states.get(null)).clear(); }
    }

    /** Only the injected memory view advances on selected failures; successful writes use real Android backing. */
    private static final class MemoryAdvancePreferences implements SharedPreferences {
        private final SharedPreferences backing;
        private Map<String, Object> memory;
        volatile int failures;
        MemoryAdvancePreferences(SharedPreferences backing) { this.backing = backing; }
        private synchronized Map<String, Object> values() {
            return memory == null ? new HashMap<>(backing.getAll()) : new HashMap<>(memory);
        }
        public Map<String, ?> getAll() { return values(); }
        private Object value(String key, Object fallback) { Map<String, Object> all = values(); return all.containsKey(key) ? all.get(key) : fallback; }
        public String getString(String key, String fallback) { return (String) value(key, fallback); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) {
            Set<String> value = (Set<String>) value(key, fallback); return value == null ? null : new HashSet<>(value);
        }
        public int getInt(String key, int fallback) { return (Integer) value(key, fallback); }
        public long getLong(String key, long fallback) { return (Long) value(key, fallback); }
        public float getFloat(String key, float fallback) { return (Float) value(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) value(key, fallback); }
        public boolean contains(String key) { return values().containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { backing.registerOnSharedPreferenceChangeListener(listener); }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { backing.unregisterOnSharedPreferenceChangeListener(listener); }
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> updates = new HashMap<>();
                final Set<String> removals = new HashSet<>();
                boolean clear;
                private Editor put(String key, Object value) { updates.put(key, value); removals.remove(key); return this; }
                public Editor putString(String key, String value) { return value == null ? remove(key) : put(key, value); }
                public Editor putStringSet(String key, Set<String> value) { return value == null ? remove(key) : put(key, new HashSet<>(value)); }
                public Editor putInt(String key, int value) { return put(key, value); }
                public Editor putLong(String key, long value) { return put(key, value); }
                public Editor putFloat(String key, float value) { return put(key, value); }
                public Editor putBoolean(String key, boolean value) { return put(key, value); }
                public Editor remove(String key) { updates.remove(key); removals.add(key); return this; }
                public Editor clear() { clear = true; return this; }
                @SuppressWarnings("unchecked") public boolean commit() {
                    synchronized (MemoryAdvancePreferences.this) {
                        Map<String, Object> next = values(); if (clear) next.clear();
                        for (String key : removals) next.remove(key); next.putAll(updates);
                        if (failures > 0) { failures--; memory = next; return false; }
                        Editor actual = backing.edit().clear();
                        for (Map.Entry<String, Object> entry : next.entrySet()) {
                            Object value = entry.getValue(); String key = entry.getKey();
                            if (value instanceof String) actual.putString(key, (String) value);
                            else if (value instanceof Boolean) actual.putBoolean(key, (Boolean) value);
                            else if (value instanceof Integer) actual.putInt(key, (Integer) value);
                            else if (value instanceof Long) actual.putLong(key, (Long) value);
                            else if (value instanceof Float) actual.putFloat(key, (Float) value);
                            else if (value instanceof Set) actual.putStringSet(key, (Set<String>) value);
                            else throw new AssertionError("Unexpected preference fixture type");
                        }
                        boolean saved = actual.commit(); memory = saved ? null : next; return saved;
                    }
                }
                public void apply() { commit(); }
            };
        }
    }

    private static <T extends Activity> AtomicReference<T> host(ActivityScenario<T> scenario) {
        AtomicReference<T> result = new AtomicReference<>(); scenario.onActivity(result::set); return result;
    }
    @SuppressWarnings("unchecked") private static <T extends View> T tagged(Activity activity, String tag) {
        return (T) activity.findViewById(android.R.id.content).findViewWithTag(tag);
    }
    private static org.hamcrest.Matcher<View> tag(String value) { return withTagValue(is((Object) value)); }
    private static boolean textExists(Activity activity, int resource) {
        return textExists(activity.findViewById(android.R.id.content), activity.getString(resource));
    }
    private static boolean textExists(Activity activity, String text) {
        return textExists(activity.findViewById(android.R.id.content), text);
    }
    private static boolean textExists(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (textExists(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(text)) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private static int countText(View view, String text) {
        int count = view instanceof TextView && view.isShown()
                && ((TextView) view).getText().toString().equals(text) ? 1 : 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            count += countText(((ViewGroup) view).getChildAt(i), text);
        return count;
    }
    private static void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 10_000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicBoolean ready = new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            SystemClock.sleep(40);
        }
        fail("Relationship presentation did not settle");
    }
    @androidx.annotation.RequiresApi(29)
    private void capture(Activity activity, String label, String tag) throws Exception {
        for (int count = 0; count < 2; count++) {
            CountDownLatch committed = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                View root = activity.getWindow().getDecorView(); assertTrue(root.isHardwareAccelerated());
                root.getViewTreeObserver().registerFrameCommitCallback(committed::countDown); root.invalidate();
            });
            assertTrue(committed.await(5, TimeUnit.SECONDS));
        }
        String run = InstrumentationRegistry.getArguments().getString("evidence_run", "relationships");
        assertTrue(run.matches("[a-zA-Z0-9-]+"));
        File directory = new File(context.getFilesDir(), "relationship-ui/" + run);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY).takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, label + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
        AtomicReference<JSONObject> witness = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View target = tagged(activity, tag); assertNotNull(target);
            Rect visible = new Rect(); boolean shown = target.getGlobalVisibleRect(visible);
            try {
                witness.set(new JSONObject().put("tag", tag).put("shown", shown)
                        .put("clip", visible.toShortString()).put("width", target.getWidth()).put("height", target.getHeight())
                        .put("screenWidthDp", activity.getResources().getConfiguration().screenWidthDp)
                        .put("fontScale", activity.getResources().getConfiguration().fontScale));
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        Files.write(new File(directory, label + ".json").toPath(), witness.get().toString(2).getBytes(StandardCharsets.UTF_8));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View target = tagged(activity, tag); Rect visible = new Rect(); assertTrue(target.getGlobalVisibleRect(visible));
            assertEquals(target.getWidth(), visible.width()); assertEquals(target.getHeight(), visible.height());
            assertTextFit(activity.findViewById(android.R.id.content));
        });
    }
    private static void assertTextFit(View view) {
        if (view instanceof TextView && view.getTag() instanceof String && ((String) view.getTag()).startsWith("relationship_")
                && view.getVisibility() == View.VISIBLE) {
            TextView text = (TextView) view; if (text.getLayout() == null) return;
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            for (int line = 0; line < text.getLineCount(); line++) {
                assertEquals(text.getTag().toString(), 0, text.getLayout().getEllipsisCount(line));
                assertTrue(text.getTag().toString(), text.getLayout().getLineMax(line) <= width + 1);
            }
            assertTrue(text.getTag().toString(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertTextFit(((ViewGroup) view).getChildAt(i));
    }
}
