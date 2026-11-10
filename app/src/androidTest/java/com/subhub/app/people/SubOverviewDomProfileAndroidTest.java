package com.subhub.app.people;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.MainActivity;
import com.subhub.app.R;
import com.subhub.app.UiAutomationStateRule;
import com.subhub.app.onboarding.OnboardingState;
import com.subhub.app.relationships.RelationshipPanel;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.remote.RemoteUnpairedUiFixture;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.KeyholderPresentationAndroidTest;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import com.subhub.app.util.AsyncUiScope;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Sub Overview consumes cached presentation data; no profile fetch or relationship mutation. */
@RunWith(AndroidJUnit4.class)
public final class SubOverviewDomProfileAndroidTest {
    private static final String JOURNAL = "subhub_relationship_presentation";
    private static final String SUB_REF = "fixture-local-sub", DOM_REF = "fixture-peer-dom",
            LOCAL_DOM_REF = "fixture-local-dom";
    private static final PersonId PEER = new PersonId("remote_"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "_peer-generation-2");
    private static final PersonId OLD_PEER = new PersonId("remote_"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "_peer-generation-1");
    private static final int PEER_COLOR = Color.rgb(28, 154, 92), LOCAL_COLOR = Color.rgb(166, 48, 72);
    private final KeyholderPresentationAndroidTest.IntegrationFixture integration =
            new KeyholderPresentationAndroidTest.IntegrationFixture();
    @Rule public final RuleChain rules = RuleChain.outerRule(integration).around(new UiAutomationStateRule());
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final Map<String, Map<String, ?>> saved = new LinkedHashMap<>();
    private final AtomicReference<RelationshipUi.Snapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<PersonSnapshot> projection = new AtomicReference<>();
    private final AtomicInteger operations = new AtomicInteger(), remoteReads = new AtomicInteger();
    private RelationshipUi.Provider originalProvider;
    private Object journalLock, originalJournalState;
    private Map<Object, Object> journalStates;
    private String journalKey;
    private boolean journalStateExisted;
    private ProfileData peerProfile, localProfile;

    @Before @SuppressWarnings("unchecked") public void prepare() throws Exception {
        assertFalse("Use an inactive owned UI guest", LocalSessionControls.isActive(context));
        originalProvider = RelationshipUi.provider();
        for (String store : new String[] { JOURNAL, "betablocker_stats", "betablocker_achievements",
                "subhub_service_duration_ui" })
            saved.put(store, new LinkedHashMap<>(context.getSharedPreferences(store, 0).getAll()));
        Field lock = RelationshipPanel.class.getDeclaredField("JOURNAL_LOCK"); lock.setAccessible(true);
        Field states = RelationshipPanel.class.getDeclaredField("JOURNAL_STATES"); states.setAccessible(true);
        journalLock = lock.get(null); journalStates = (Map<Object, Object>) states.get(null);
        journalKey = context.getApplicationInfo().dataDir + "/" + JOURNAL;
        synchronized (journalLock) {
            journalStateExisted = journalStates.containsKey(journalKey);
            originalJournalState = journalStates.remove(journalKey);
        }
        assertTrue(context.getSharedPreferences(JOURNAL, 0).edit().clear().commit());
        assertTrue(ControllerPinManager.setPin(context, "5729"));
        assertTrue(ControllerPinManager.verify(context, "5729"));
        assertTrue(DeviceRolePreferences.set(context, DeviceRolePreferences.Mode.BOTH));
        OnboardingState.complete(context);
        ControllerPinManager.enterSubMode();
        peerProfile = profile("Cedar", "Cached counterpart biography.", PEER_COLOR, "Non-binary", 37);
        localProfile = profile("Local decoy", "This device's unrelated Dom.", LOCAL_COLOR, "Woman", 52);
        People.installKeyholder(KeyholderPresentationAndroidTest.provider(KeyholderProvider.State.UNAVAILABLE, ""));
        People.installRemoteAuthority(() -> true);
        People.installLocalProjection(local -> projection.get());
        People.installRemoteRepository(new PersonRepository() {
            @Override public java.util.List<PersonId> people() { remoteReads.incrementAndGet(); return Collections.emptyList(); }
            @Override public PersonSnapshot load(PersonId id) { remoteReads.incrementAndGet(); throw new AssertionError("No counterpart fetch"); }
        });
        projection.set(person(PEER, peerProfile, PersonSnapshot.Freshness.CURRENT));
        snapshot.set(relationship(RelationshipUi.Delivery.PEER, PEER, DOM_REF));
        RelationshipUi.install(new RelationshipUi.Provider() {
            @Override public CompletableFuture<RelationshipUi.Snapshot> snapshot() { return CompletableFuture.completedFuture(snapshot.get()); }
            @Override public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String key, String dom, String sub, String revision) { return mutation(); }
            @Override public CompletableFuture<RelationshipUi.Operation> confirmLocal(String id, String digest) { return mutation(); }
            @Override public CompletableFuture<RelationshipUi.Operation> resume(String id) { return mutation(); }
            @Override public CompletableFuture<RelationshipUi.Operation> cancel(String id) { return mutation(); }
        });
    }

    @After public void restore() {
        try {
            if (originalProvider != null) RelationshipUi.install(originalProvider);
            // A failed preference restore must not prevent the remaining stores/cache from being restored.
            AssertionError failure = null;
            for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet()) {
                try { SharedPreferenceTestRestore.restore(context.getSharedPreferences(entry.getKey(), 0), entry.getValue()); }
                catch (AssertionError error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        } finally {
            if (journalLock != null) synchronized (journalLock) {
                if (journalStateExisted) journalStates.put(journalKey, originalJournalState);
                else journalStates.remove(journalKey);
            }
        }
    }

    @Test public void exactPeerProfileRendersAndRemainsVisibleWhenCachedContractIsStale() throws Exception {
        try (RemoteUnpairedUiFixture backend = new RemoteUnpairedUiFixture(context);
                ActivityScenario<MainActivity> page = ActivityScenario.launch(home())) {
            await(page, activity -> identityReady(activity, peerProfile));
            AtomicReference<View> tasks = new AtomicReference<>(), journal = new AtomicReference<>(), session = new AtomicReference<>();
            page.onActivity(activity -> {
                assertIdentity(activity, peerProfile, PEER_COLOR);
                tasks.set(tag(activity, "home_tasks")); journal.set(tag(activity, "home_journal:sub"));
                session.set(tag(activity, "overview_session_state"));
                assertNotNull(tasks.get()); assertNotNull(journal.get()); assertNotNull(session.get());
            });
            projection.set(person(PEER, peerProfile, PersonSnapshot.Freshness.STALE));
            refresh(page);
            await(page, activity -> identityReady(activity, peerProfile)
                    && hasText(activity.findViewById(R.id.home_assigned_dom), activity.getString(R.string.role_stale_data)));
            page.onActivity(activity -> {
                assertIdentity(activity, peerProfile, PEER_COLOR);
                assertSame(tasks.get(), tag(activity, "home_tasks"));
                assertSame(journal.get(), tag(activity, "home_journal:sub"));
                assertSame(session.get(), tag(activity, "overview_session_state"));
                assertFalse(ControllerPinManager.isDomModeActive());
                assertFalse(ControllerPinManager.isSessionUnlocked());
                assertFalse(ControllerPinManager.canEditConfiguration());
            });
            assertEquals(PersonSnapshot.Freshness.STALE, projection.get().sync.contract);
            assertEquals(RelationshipUi.Availability.UNKNOWN, snapshot.get().governingDom.availability);
            assertEquals(PersonSnapshot.Freshness.UNKNOWN, snapshot.get().governingDom.freshness);
            assertEquals(0, operations.get()); assertEquals(0, remoteReads.get());
        }
    }

    @Test public void wrongPeerGenerationAndLocalProjectionStayUnknownWhileExplicitLocalEdgeUsesExactParticipant() throws Exception {
        projection.set(person(OLD_PEER, peerProfile, PersonSnapshot.Freshness.STALE));
        try (RemoteUnpairedUiFixture backend = new RemoteUnpairedUiFixture(context);
                ActivityScenario<MainActivity> page = ActivityScenario.launch(home())) {
            await(page, SubOverviewDomProfileAndroidTest::unknownReady);
            page.onActivity(this::assertUnknown);
            projection.set(person(new PersonId("journal_local_dom"), localProfile, PersonSnapshot.Freshness.CURRENT));
            refresh(page);
            await(page, SubOverviewDomProfileAndroidTest::unknownReady);
            page.onActivity(this::assertUnknown);
            // The same participant is a valid display source only when the governing delivery is explicitly LOCAL.
            snapshot.set(relationship(RelationshipUi.Delivery.LOCAL, PersonId.LOCAL, LOCAL_DOM_REF));
            refresh(page);
            await(page, activity -> identityReady(activity, localProfile));
            page.onActivity(activity -> assertIdentity(activity, localProfile, LOCAL_COLOR));
            snapshot.set(relationship(RelationshipUi.Delivery.LOCAL, PersonId.LOCAL, "absent-dom"));
            refresh(page);
            await(page, SubOverviewDomProfileAndroidTest::unknownReady);
            page.onActivity(this::assertUnknown);
            assertEquals(0, operations.get()); assertEquals(0, remoteReads.get());
        }
    }

    private PersonSnapshot person(PersonId domId, ProfileData dom, PersonSnapshot.Freshness freshness) {
        return new PersonSnapshot(PersonId.LOCAL, "Fixture Sub", "Fixture device", "Fixture contract", "Inactive",
                PersonSnapshot.Connection.LOCAL, PersonSnapshot.Freshness.CURRENT, 0,
                Collections.emptySet(), Collections.emptyList(), Collections.emptyList(), "EUR", 0, 0, 0, 0, false,
                new PersonSnapshot.SyncState(freshness, PersonSnapshot.Freshness.CURRENT, PersonSnapshot.Freshness.CURRENT, "", ""))
                .withContractDom(dom, domId, domId.value().startsWith("remote_")
                        ? new PersonSnapshot.PeerContact(domId, PersonSnapshot.Connection.OFFLINE, 1000) : null);
    }

    private RelationshipUi.Snapshot relationship(RelationshipUi.Delivery delivery, PersonId personId, String domRef) {
        return new RelationshipUi.Snapshot(true, "fixture-revision", Arrays.asList(
                new RelationshipUi.Participant(SUB_REF, RelationshipUi.Role.SUB, ProfileData.empty("Fixture Sub")),
                new RelationshipUi.Participant(LOCAL_DOM_REF, RelationshipUi.Role.DOM, localProfile)),
                Collections.emptyList(), new RelationshipUi.Edge("formal-relationship", "formal-generation-7",
                        domRef, SUB_REF, delivery, "ACTIVE", 1, personId, RelationshipUi.Availability.UNKNOWN,
                        PersonSnapshot.Freshness.UNKNOWN), Collections.emptyList());
    }

    private <T> CompletableFuture<T> mutation() {
        operations.incrementAndGet();
        CompletableFuture<T> failed = new CompletableFuture<>();
        failed.completeExceptionally(new AssertionError("Read-only overview must not mutate relationships"));
        return failed;
    }

    private Intent home() { return new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN); }

    private static void refresh(ActivityScenario<MainActivity> page) {
        page.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("roleHome"); field.setAccessible(true);
                ((RoleHomePresenter) field.get(activity)).refresh(true);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    private static void await(ActivityScenario<MainActivity> page, Predicate<MainActivity> condition) {
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        do {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            page.onActivity(activity -> ready.set(!AsyncUiScope.forPage(activity).isPending("role-home") && condition.test(activity)));
            if (ready.get()) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Sub Overview did not render the expected cached identity");
    }

    private static View tag(MainActivity activity, String value) { return activity.getWindow().getDecorView().findViewWithTag(value); }
    private static boolean identityReady(MainActivity activity, ProfileData profile) {
        View name = tag(activity, "home_contract_dom_name"), avatar = tag(activity, "home_contract_dom_avatar");
        return name instanceof TextView && profile.name.contentEquals(((TextView) name).getText())
                && avatar != null && avatar.getWidth() > 0 && imageReady(avatar);
    }
    private static boolean imageReady(View view) {
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE)
            return ((ImageView) view).getDrawable() != null;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (imageReady(((ViewGroup) view).getChildAt(index))) return true;
        return false;
    }
    private static boolean unknownReady(MainActivity activity) {
        View name = tag(activity, "home_contract_dom_name");
        return name instanceof TextView && activity.getString(R.string.role_unknown_data).contentEquals(((TextView) name).getText());
    }
    private void assertUnknown(MainActivity activity) {
        assertTrue(unknownReady(activity));
        View connection = activity.findViewById(R.id.home_assigned_dom);
        assertEquals(View.VISIBLE, connection.getVisibility());
        assertFalse(hasText(connection, peerProfile.name)); assertFalse(hasText(connection, localProfile.name));
        assertNull(tag(activity, "home_dom_about"));
        assertFalse(imageReady(tag(activity, "home_contract_dom_avatar")));
    }
    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++)
            if (hasText(((ViewGroup) view).getChildAt(index), text)) return true;
        return false;
    }
    private void assertIdentity(MainActivity activity, ProfileData profile, int color) {
        assertTrue(identityReady(activity, profile));
        TextView details = (TextView) tag(activity, "home_contract_dom_details");
        assertTrue(details.getText().toString().contains(profile.gender));
        assertTrue(details.getText().toString().contains(activity.getResources()
                .getQuantityString(R.plurals.profile_age_value, profile.age, profile.age)));
        assertEquals(profile.about, ((TextView) tag(activity, "home_dom_about")).getText().toString());
        View avatar = tag(activity, "home_contract_dom_avatar");
        Bitmap rendered = Bitmap.createBitmap(avatar.getWidth(), avatar.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            // Draw the attached native avatar after its real async decode; no fabricated bitmap/readiness field.
            avatar.draw(new Canvas(rendered));
            int observed = rendered.getPixel(rendered.getWidth() / 2, rendered.getHeight() / 2);
            boolean matches = Math.abs(Color.red(observed) - Color.red(color)) <= 8
                    && Math.abs(Color.green(observed) - Color.green(color)) <= 8
                    && Math.abs(Color.blue(observed) - Color.blue(color)) <= 8;
            if (!matches) {
                File directory = new File(context.getExternalFilesDir(null), "sub-overview-dom-profile-failures");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File evidence = new File(directory, "avatar-" + System.nanoTime() + ".png");
                try (FileOutputStream output = new FileOutputStream(evidence)) {
                    assertTrue(rendered.compress(Bitmap.CompressFormat.PNG, 100, output));
                } catch (java.io.IOException error) { throw new AssertionError(error); }
            }
            assertTrue("Native avatar center differs from the synthetic counterpart thumbnail", matches);
        } finally { rendered.recycle(); }
    }
    private static ProfileData profile(String name, String about, int color, String gender, int age) throws Exception {
        Bitmap image = Bitmap.createBitmap(ProfileImages.EDGE, ProfileImages.EDGE, Bitmap.Config.ARGB_8888);
        try { image.eraseColor(color); return new ProfileData(name, about, ProfileImages.encode(image), gender, age); }
        finally { image.recycle(); }
    }
}
