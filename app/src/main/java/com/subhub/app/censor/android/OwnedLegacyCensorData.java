package com.subhub.app.censor.android;

import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.penance.InternalPenanceRules;
import com.subhub.app.penance.OwnerPenanceLedger;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeEnvironment;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.StatsRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Actual owner broker adapter for legacy track/tap events, never modern evidence or DEVICE data. */
public final class OwnedLegacyCensorData implements AutoCloseable {
    public interface LocalNotice {
        /** Already ACKed, delivered under fresh consumer NORMAL. No physical payment/remote sink. */
        void assessed(PenanceInfraction kind, int cents, String currency, OwnerObservation observed);
    }
    public static final class Facts {
        public final RuntimeConfiguration configuration;
        public final int dwellSeconds;
        private final InternalPenanceRules rules;
        private final boolean censorEnabled;
        private final AchievementManager.OwnerFacts achievements;
        private Facts(RuntimeConfiguration configuration, AchievementManager.OwnerFacts achievements) {
            this.configuration = configuration; this.achievements = achievements;
            rules = new InternalPenanceRules(configuration.penance);
            dwellSeconds = rules.getDwellSeconds();
            censorEnabled = configuration.main.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true);
        }
    }
    public static final class Assessment {
        public final int newTracks, detectionCents, dwellCents, tapCents;
        public final String currency;
        public final OwnerObservation observed;
        Assessment(int tracks, int detection, int dwell, int tap, Facts facts, OwnerObservation observed) {
            newTracks = tracks; detectionCents = detection; dwellCents = dwell; tapCents = tap;
            currency = facts.rules.getCurrency(); this.observed = observed;
        }
        public void notifyLocal(LocalNotice notice) {
            Objects.requireNonNull(notice);
            if (detectionCents > 0) notice.assessed(PenanceInfraction.NEW_DETECTION, detectionCents, currency, observed);
            if (dwellCents > 0) notice.assessed(PenanceInfraction.CENSORED_DWELL, dwellCents, currency, observed);
            if (tapCents > 0) notice.assessed(PenanceInfraction.CENSORED_TAP, tapCents, currency, observed);
        }
    }
    private final RuntimeEnvironment runtime;
    private final InternalOwnerDataBroker.Session session;
    private final StatsRepository.SessionStamp stamp;
    private final CensorAccountingQueue queue;
    private final Function<RuntimeConfiguration, AchievementManager.OwnerFacts> achievements;
    private final LegacyTrackIds trackIds;
    private volatile RuntimeConfiguration adopted, acknowledged;
    private volatile boolean closed;

    /** Exact session-local sequence must be retained by factory across capture-child replacement. */
    public OwnedLegacyCensorData(RuntimeEnvironment runtime, InternalOwnerDataBroker.Session session,
            RuntimeConfiguration initial, LegacyTrackIds sessionTrackIds,
            Function<RuntimeConfiguration, AchievementManager.OwnerFacts> achievements) {
        this.runtime = Objects.requireNonNull(runtime); this.session = Objects.requireNonNull(session);
        this.trackIds = Objects.requireNonNull(sessionTrackIds); this.achievements = Objects.requireNonNull(achievements);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL || !runtime.identity().equals(session.identity()))
            throw new SecurityException("Different legacy accounting owner");
        requireConfiguration(initial);
        if (initial != runtime.configuration() || initial.configRevision != runtime.appliedConfigurationRevision())
            throw new SecurityException("Unacknowledged initial legacy configuration");
        stamp = session.acknowledgedSessionStamp();
        if (stamp == null || stamp.isDeviceSession() || !session.isCurrentStamp(stamp))
            throw new SecurityException("Missing acknowledged owner statistics session");
        adopted = initial; acknowledged = initial;
        queue = new CensorAccountingQueue(action -> {
            try { return session.executeNormal(action); }
            catch (OwnerWriteQueue.AdmissionRejected refused) { throw new CensorAccountingQueue.AdmissionRefused(refused); }
        }, failure -> runtime.requestStop());
    }
    public void requireBinding(RuntimeEnvironment expected, InternalOwnerDataBroker.Session data) {
        if (expected != runtime || data != session) throw new SecurityException("Different legacy data binding");
    }
    public LegacyTrackIds.Epoch nextTrackerEpoch() { return trackIds.nextEpoch(); }
    public void adopt(RuntimeConfiguration configuration) {
        requireConfiguration(configuration);
        if (closed || configuration.configRevision < adopted.configRevision) throw new SecurityException("Stale legacy configuration");
        adopted = configuration; acknowledged = null;
    }
    public void acknowledged(RuntimeConfiguration configuration) {
        requireConfiguration(configuration);
        if (closed || adopted != configuration) throw new SecurityException("Unadopted legacy configuration ACK");
        acknowledged = configuration;
    }
    /** Call under the event's exact NORMAL admission, before enqueueing immutable data. */
    public Facts capture(RuntimeConfiguration configuration) {
        if (closed || adopted != configuration || acknowledged != configuration || runtime.configuration() != configuration
                || runtime.appliedConfigurationRevision() != configuration.configRevision || !session.isCurrentStamp(stamp))
            throw new SecurityException("Legacy accounting configuration/source is not current");
        return new Facts(configuration, Objects.requireNonNull(achievements.apply(configuration)));
    }
    public CompletableFuture<Assessment> recordFrame(Facts facts, List<Integer> ids, Set<String> categories,
            int dwellCount, OwnerObservation observed) {
        requireFacts(facts); Objects.requireNonNull(observed);
        if (dwellCount < 0) throw new IllegalArgumentException("Negative dwell count");
        List<Integer> copiedIds = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(ids)));
        Set<String> copiedCategories = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(categories)));
        java.util.concurrent.atomic.AtomicReference<Assessment> result = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> ack = queue.submit(() -> {
            int count = session.recordLegacyTrackIds(stamp, copiedIds, copiedCategories, observed);
            int detection = count == 0 ? 0 : charged(session.assessDetection(stamp, count, facts.rules, facts.censorEnabled, observed));
            if (count > 0) session.reconcileAchievements(stamp, facts.achievements, observed);
            int dwell = dwellCount == 0 ? 0 : charged(session.assessDwell(stamp, dwellCount, facts.rules, facts.censorEnabled, observed));
            result.set(new Assessment(count, detection, dwell, 0, facts, observed));
        });
        return ack.thenApply(ignored -> Objects.requireNonNull(result.get()));
    }
    /** Geometry/focus/config and 500ms debounce were already proven by the owning consumer. */
    public CompletableFuture<Assessment> recordTap(Facts facts, OwnerObservation observed) {
        requireFacts(facts); Objects.requireNonNull(observed);
        java.util.concurrent.atomic.AtomicReference<Assessment> result = new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> ack = queue.submit(() -> result.set(new Assessment(0, 0, 0,
                charged(session.assessCensoredTap(stamp, facts.rules, facts.censorEnabled, observed)), facts, observed)));
        return ack.thenApply(ignored -> Objects.requireNonNull(result.get()));
    }
    private void requireFacts(Facts facts) {
        Objects.requireNonNull(facts); requireConfiguration(facts.configuration);
        if (closed) throw new IllegalStateException("Legacy accounting closed");
    }
    private void requireConfiguration(RuntimeConfiguration configuration) {
        if (configuration == null || !runtime.identity().equals(configuration.owner))
            throw new SecurityException("Different legacy configuration owner");
    }
    private static int charged(OwnerPenanceLedger.Result result) {
        if (result.status != OwnerPenanceLedger.Status.ACKNOWLEDGED)
            throw new SecurityException("Legacy assessment was not acknowledged");
        return result.chargedCents;
    }
    @Override public void close() { closed = true; queue.close(); }
    public boolean managedResourcesDrained() { return closed && queue.drained(); }
}
