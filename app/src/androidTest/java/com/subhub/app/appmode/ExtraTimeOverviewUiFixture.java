package com.subhub.app.appmode;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import com.subhub.app.people.People;
import com.subhub.app.people.PersonId;
import com.subhub.app.people.PersonSnapshot;
import com.subhub.app.people.ProfileData;
import com.subhub.app.relationships.DomScopeObserverFixture;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.remote.RemoteFeature;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.settings.SharedPreferenceTestRestore;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/** Read-only Home presentation over the real paired-domain fixture; it grants no request authority. */
final class ExtraTimeOverviewUiFixture implements AutoCloseable {
    private final Context context;
    private final RelationshipUi.Provider originalProvider = RelationshipUi.provider();
    private final UnaryOperator<PersonSnapshot> originalProjection;
    private final SharedPreferences achievements;
    private final Map<String, ?> originalAchievements;
    private final AtomicReference<PersonSnapshot.Connection> connection =
            new AtomicReference<>(PersonSnapshot.Connection.CONNECTED);
    private final long lastOnlineAt = System.currentTimeMillis() - 60_000L;
    private final PersonId domId;
    private DomScopeObserverFixture presentation;
    private boolean closed;

    @SuppressWarnings("unchecked")
    ExtraTimeOverviewUiFixture(Context context) throws Exception {
        this.context = context.getApplicationContext();
        Field projection = People.class.getDeclaredField("localProjection"); projection.setAccessible(true);
        originalProjection = (UnaryOperator<PersonSnapshot>) projection.get(null);
        achievements = this.context.getSharedPreferences("betablocker_achievements", 0);
        originalAchievements = new LinkedHashMap<>(achievements.getAll());
        RemoteStore.Peer current = null;
        for (RemoteStore.Peer peer : RemoteFeature.get(context).store.peers(false))
            if (peer.state == RemoteStore.State.ACTIVE && peer.localRole == RemoteProtocol.Role.SUB) current = peer;
        assertNotNull("The real paired Sub endpoint must exist before its display fixture", current);
        domId = RemotePersonRepository.id(current);
        try {
            // Both stores are already typed-snapshotted by RemoteExtraTimeUiFixture.
            assertTrue(this.context.getSharedPreferences("subhub_onboarding", 0).edit().putBoolean("completed", true).commit());
            assertTrue(this.context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                    .edit().putBoolean("has_seen_onboarding", true).commit());
            presentation = new DomScopeObserverFixture(this.context);
            RelationshipUi.Participant sub = new RelationshipUi.Participant("overview-ui-sub", RelationshipUi.Role.SUB,
                    ProfileData.empty("Alex"));
            RelationshipUi.Edge edge = new RelationshipUi.Edge("overview-ui-edge", "overview-ui-generation",
                    "overview-ui-dom", sub.ref, RelationshipUi.Delivery.PEER, "ACTIVE", 1, domId,
                    RelationshipUi.Availability.AVAILABLE, PersonSnapshot.Freshness.CURRENT);
            RelationshipUi.Snapshot snapshot = new RelationshipUi.Snapshot(true, UUID.randomUUID().toString(),
                    Collections.singletonList(sub), Collections.emptyList(), edge, Collections.emptyList());
            RelationshipUi.install(new ReadOnlyProvider(snapshot));
            decorate(PersonSnapshot.Connection.CONNECTED);
            // Consume genuinely earned fixture notices before taps; no invented achievement awards.
            com.subhub.app.stats.HomeAchievementPreview.load(this.context,
                    new com.subhub.app.stats.StatsRepository(this.context).load()).achievements.takePendingNotifications();
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void decorate(PersonSnapshot.Connection state) {
        connection.set(state);
        People.installLocalProjection(person -> originalProjection.apply(person).withContractDom(ProfileData.empty("Morgan"),
                domId, new PersonSnapshot.PeerContact(domId, connection.get(), lastOnlineAt)));
    }

    void assertKnownContact() throws Exception {
        PersonSnapshot person = People.repository(context).load(PersonId.LOCAL);
        assertEquals("Morgan", person.contractDom.name);
        assertEquals(domId, person.contractDomId);
        assertNotNull(person.contractDomContact);
        assertEquals(connection.get(), person.contractDomContact.state);
        assertTrue(person.contractDomContact.lastOnlineAt > 0);
    }

    @Override public void close() {
        if (closed) return; closed = true;
        try { People.installLocalProjection(originalProjection); }
        finally {
            try {
                if (presentation != null) presentation.close();
                else RelationshipUi.install(originalProvider);
            } finally {
                SharedPreferenceTestRestore.restore(achievements, originalAchievements);
                assertEquals(originalAchievements, achievements.getAll());
                assertSame(originalProvider, RelationshipUi.provider());
            }
        }
    }

    private static final class ReadOnlyProvider implements RelationshipUi.Provider {
        private final RelationshipUi.Snapshot snapshot;
        ReadOnlyProvider(RelationshipUi.Snapshot snapshot) { this.snapshot = snapshot; }
        public CompletableFuture<RelationshipUi.Snapshot> snapshot() { return CompletableFuture.completedFuture(snapshot); }
        public CompletableFuture<RelationshipUi.LocalReview> prepareLocal(String k, String d, String s, String r) {
            throw new AssertionError("Overview display fixture cannot prepare relationships");
        }
        public CompletableFuture<RelationshipUi.Operation> confirmLocal(String i, String d) { throw new AssertionError("Read-only fixture"); }
        public CompletableFuture<RelationshipUi.Operation> resume(String i) { throw new AssertionError("Read-only fixture"); }
        public CompletableFuture<RelationshipUi.Operation> cancel(String i) { throw new AssertionError("Read-only fixture"); }
    }
}
