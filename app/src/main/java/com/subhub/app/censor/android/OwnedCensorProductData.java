package com.subhub.app.censor.android;

import android.content.SharedPreferences;
import android.graphics.RectF;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.internal.InternalOwnerDataBroker;
import com.subhub.app.penance.InternalPenanceRules;
import com.subhub.app.penance.OwnerPenanceLedger;
import com.subhub.app.penance.PenanceInfraction;
import com.subhub.app.popup.ScopedDetectionHints;
import com.subhub.app.runtime.RuntimeConfiguration;
import com.subhub.app.runtime.RuntimeExecution;
import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.runtime.RuntimeTarget;
import com.subhub.app.settings.FeatureModuleManager;
import com.subhub.app.stats.AchievementManager;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerWriteQueue;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.StatsSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Explicit INTERNAL data binding. No Context, physical store, payment, remote sink or current-owner
 * lookup. Construct on the owner worker only after its STARTUP statistics receipt acknowledged.
 * A registered Censor component must retain the product until its native and accounting drain.
 * This class alone cannot start capture or admit an OS effect.
 */
final class OwnedCensorProductData implements CensorProductData {
    interface LocalNotice {
        /** Bind a counted exact-owner notification implementation, never the physical notifier. */
        void assessed(RuntimeLease.Identity owner, PenanceInfraction kind, int cents, String currency,
                OwnerObservation observed, BooleanSupplier stillCurrent);
    }
    private final RuntimeExecution<RuntimeConfiguration> runtime;
    private final InternalOwnerDataBroker.Session session;
    private final StatsRepository.SessionStamp stamp;
    private final Supplier<String> inputMethod;
    private final BooleanSupplier protectionEligible;
    private final ScopedDetectionHints<RectF> popupHints;
    private final Function<RuntimeConfiguration, AchievementManager.OwnerFacts> facts;
    private final LocalNotice notices;
    private final CensorAccountingQueue queue;
    private volatile RuntimeConfiguration rendering;
    private volatile EventFacts accounting;
    private volatile boolean closed;
    private final Participation participation = new Participation() {
        @Override public boolean lab() { return false; }
        @Override public boolean current() { return !closed && session.isCurrentStamp(stamp); }
        @Override public <T> T admitNormal(Supplier<T> admission, T rejected) {
            // The accounting callback already owns its exact NORMAL writer permit. This short
            // predicate cannot turn an identity or stamp into authority, or reacquire after Stop.
            return current() ? admission.get() : rejected;
        }
    };
    private final AccountingWorker worker = new AccountingWorker() {
        @Override public void execute(Runnable action) { queue.execute(action); }
        @Override public void close() { closed = true; queue.close(); }
        @Override public boolean terminated() { return queue.drained(); }
    };

    OwnedCensorProductData(RuntimeExecution<RuntimeConfiguration> runtime, InternalOwnerDataBroker.Session session,
            RuntimeConfiguration initial, Supplier<String> inputMethod, ScopedDetectionHints<RectF> popupHints,
            BooleanSupplier ownedProtectionEligible,
            Function<RuntimeConfiguration, AchievementManager.OwnerFacts> capturedFacts, LocalNotice notices) {
        this.runtime = Objects.requireNonNull(runtime); this.session = Objects.requireNonNull(session);
        if (runtime.identity().target.kind != RuntimeTarget.Kind.INTERNAL || !runtime.identity().equals(session.identity()))
            throw new SecurityException("Censor data belongs to another runtime");
        requireConfiguration(initial);
        if (initial != runtime.configuration() || initial.configRevision != runtime.appliedConfigurationRevision())
            throw new SecurityException("Censor needs the runtime's applied configuration");
        stamp = session.acknowledgedSessionStamp();
        if (stamp == null || stamp.isDeviceSession() || !session.isCurrentStamp(stamp))
            throw new SecurityException("Internal Censor needs its acknowledged active statistics lane");
        this.inputMethod = Objects.requireNonNull(inputMethod); this.popupHints = Objects.requireNonNull(popupHints);
        // The gateway supplies this exact owner's pause/eligibility state, never DEVICE globals.
        // A live accounting stamp alone does not mean protection is currently eligible to draw.
        protectionEligible = Objects.requireNonNull(ownedProtectionEligible);
        facts = Objects.requireNonNull(capturedFacts); this.notices = Objects.requireNonNull(notices);
        rendering = initial; accounting = new EventFacts(initial);
        queue = new CensorAccountingQueue(action -> {
            try { return session.executeNormal(action); }
            catch (OwnerWriteQueue.AdmissionRejected refused) {
                // This synchronous typed receipt proves no executor invocation. Future/write
                // failures are never translated into a refusal or an invented drain receipt.
                throw new CensorAccountingQueue.AdmissionRefused(refused);
            }
        }, ignored -> runtime.requestStop());
    }
    /** Called only by the component's admitted module receiver, before the owner-journal ACK. */
    void applyRendering(RuntimeConfiguration configuration) {
        requireConfiguration(configuration);
        if (closed || configuration.configRevision < rendering.configRevision)
            throw new SecurityException("Stale Censor configuration");
        rendering = configuration;
    }
    /** Called only by ConfigurationReceiver.acknowledged on the serial owner worker. */
    void acknowledged(RuntimeConfiguration configuration) {
        requireConfiguration(configuration);
        if (closed || rendering != configuration || configuration.configRevision < accounting.configuration.configRevision)
            throw new SecurityException("Unapplied Censor accounting configuration");
        accounting = new EventFacts(configuration);
    }
    private void requireConfiguration(RuntimeConfiguration configuration) {
        if (configuration == null || !runtime.identity().equals(configuration.owner))
            throw new SecurityException("Different Censor configuration owner");
    }
    @Override public SharedPreferences preferences() { return rendering.main; }
    @Override public String inputMethodPackage() { return Objects.requireNonNull(inputMethod.get()); }
    @Override public Participation participation() { return participation; }
    @Override public StatsRepository.SessionStamp observedSessionStamp() { return session.isCurrentStamp(stamp) ? stamp : null; }
    @Override public StatsRepository.SessionStamp sessionStamp() { return stamp; }
    @Override public AccountingWorker accountingWorker() { return worker; }
    @Override public Ledger dwellLedger() { return accounting; }
    @Override public Ledger detectionLedger() { return accounting; }
    @Override public AccountingFacts accountingFacts() { return accounting; }
    @Override public boolean armed(SharedPreferences ignored) { return participation.current() && protectionEligible.getAsBoolean(); }
    @Override public StatsRepository.EvidenceRecord recordEvidence(StatsRepository.SessionStamp source,
            Supplier<CensorEngine.EvidenceClaim> claim, Set<String> categories, OwnerObservation observed) {
        requireSource(source);
        return session.recordEvidence(source, claim, categories, Objects.requireNonNull(observed));
    }
    @Override public void checkAchievements(StatsSnapshot snapshot) {
        throw new IllegalStateException("Owned achievements require the event's captured facts and clock");
    }
    @Override public ScopedDetectionHints<RectF>.Binding bindPopupHints(Supplier<List<RectF>> hints) {
        return popupHints.bind(hints);
    }
    @Override public void accountingFailed() { runtime.requestStop(); }
    private void requireSource(StatsRepository.SessionStamp source) {
        if (source != stamp || !session.isCurrentStamp(source))
            throw new SecurityException("Different Censor statistics source");
    }
    private final class EventFacts implements AccountingFacts, Ledger {
        final RuntimeConfiguration configuration;
        final InternalPenanceRules rules;
        final AchievementManager.OwnerFacts achievements;
        final boolean censorEnabled;
        EventFacts(RuntimeConfiguration configuration) {
            this.configuration = configuration;
            rules = new InternalPenanceRules(configuration.penance);
            achievements = Objects.requireNonNull(facts.apply(configuration));
            censorEnabled = configuration.main.getBoolean(FeatureModuleManager.KEY_CENSOR_ENABLED, true);
        }
        @Override public Ledger dwellLedger() { return this; }
        @Override public Ledger detectionLedger() { return this; }
        @Override public int dwellSeconds() { return rules.getDwellSeconds(); }
        @Override public Assessment detection(int count, long ignored, StatsRepository.SessionStamp source, OwnerObservation observed) {
            requireSource(source);
            return assessment(session.assessDetection(source, count, rules, censorEnabled, Objects.requireNonNull(observed)));
        }
        @Override public Assessment dwell(long ignored, StatsRepository.SessionStamp source, OwnerObservation observed) {
            requireSource(source);
            return assessment(session.assessDwell(source, 1, rules, censorEnabled, Objects.requireNonNull(observed)));
        }
        @Override public void checkAchievements(StatsSnapshot ignored, OwnerObservation observed) {
            session.reconcileAchievements(stamp, achievements, Objects.requireNonNull(observed));
        }
        @Override public void notifyCharge(PenanceInfraction kind, int cents, long ignored,
                OwnerObservation observed, BooleanSupplier stillCurrent) {
            notices.assessed(runtime.identity(), kind, cents, rules.getCurrency(), Objects.requireNonNull(observed), stillCurrent);
        }
    }
    private static Assessment assessment(OwnerPenanceLedger.Result result) {
        return new Assessment(result.status == OwnerPenanceLedger.Status.ACKNOWLEDGED, result.chargedCents);
    }
}
