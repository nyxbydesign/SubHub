package com.subhub.app.service;

import com.subhub.app.runtime.RuntimeLease;
import com.subhub.app.stats.OwnerObservation;
import java.util.Objects;

/** Exact acknowledged foreground evidence. This bookkeeping grants no runtime effect permit. */
final class InternalLimitsAdmission<C> {
    static final class Observation<C> {
        final C configuration;
        final long epoch, monotonic, usageMillis;
        final OwnerObservation calendar;
        final String packageName;
        final int windowId;
        final boolean qualified, accountingAllowed, protectionEligible, limitsEnabled;
        Observation(C configuration, long epoch, long monotonic, long usageMillis,
                OwnerObservation calendar, String packageName, int windowId,
                boolean qualified, boolean accountingAllowed, boolean protectionEligible, boolean limitsEnabled) {
            this.configuration = configuration; this.epoch = epoch; this.monotonic = monotonic;
            this.usageMillis = usageMillis; this.calendar = calendar; this.packageName = packageName;
            this.windowId = windowId; this.qualified = qualified;
            this.accountingAllowed = accountingAllowed; this.protectionEligible = protectionEligible;
            this.limitsEnabled = limitsEnabled;
        }
    }
    static final class Decision<C> {
        final Observation<C> observation;
        final boolean blocked;
        Decision(Observation<C> observation, boolean blocked) {
            this.observation = observation; this.blocked = blocked;
        }
    }
    private final RuntimeLease.Identity owner;
    private final ForegroundUsageSession usage = new ForegroundUsageSession();
    private final ForegroundLimitHandoff handoff = new ForegroundLimitHandoff();
    private C configuration;
    private long epoch;
    private boolean open = true;
    private Observation<C> pending;
    private Decision<C> acknowledged;

    InternalLimitsAdmission(RuntimeLease.Identity owner, C configuration) {
        this.owner = Objects.requireNonNull(owner); this.configuration = Objects.requireNonNull(configuration);
    }
    synchronized long invalidate(boolean resetContinuity) {
        acknowledged = null; pending = null;
        epoch = Math.addExact(epoch, 1L);
        if (resetContinuity) usage.reset();
        return epoch;
    }
    synchronized void adopt(C next) {
        configuration = Objects.requireNonNull(next);
        // Normal Limits keeps continuous qualification across a rule edit. The next real sample
        // resets it if that package/rule is no longer enabled; no old ACK may publish new rules.
        invalidate(false);
    }
    synchronized boolean current(C image, long observedEpoch) {
        return open && image == configuration && observedEpoch == epoch;
    }
    synchronized Observation<C> observe(C image, long observedEpoch, String packageName, int windowId,
            long monotonic, OwnerObservation calendar, boolean enabled, boolean selected,
            boolean protectionEligible) {
        Objects.requireNonNull(calendar); Objects.requireNonNull(packageName);
        if (!current(image, observedEpoch)) return null;
        if (pending != null) throw new IllegalStateException("Foreground observation already awaits acknowledgement");
        String known = windowId < 0 ? "" : packageName;
        if (!known.isEmpty()) handoff.observed(known); // Unknown is not an observed departure.
        long delta = 0;
        if (known.isEmpty() || !enabled || !selected || !protectionEligible || !handoff.accountingAllowed()) usage.reset();
        else delta = usage.observe(known, monotonic);
        pending = new Observation<>(image, observedEpoch, monotonic, delta, calendar, known,
                known.isEmpty() ? -1 : windowId, usage.qualified(known), handoff.accountingAllowed(), protectionEligible, enabled);
        return pending;
    }
    synchronized Decision<C> acknowledge(Observation<C> observed, boolean budgetReached) {
        if (!current(observed.configuration, observed.epoch) || pending != observed) return null;
        // A missing/locked window is not proof that Home completed. A disabled rule, however,
        // releases the handoff just as the ordinary Limits path does.
        boolean unknown = observed.packageName.isEmpty() && observed.limitsEnabled;
        if (!unknown) handoff.budgetReached(budgetReached);
        boolean blocked = unknown ? !handoff.accountingAllowed()
                : budgetReached && (!handoff.accountingAllowed() || observed.qualified);
        acknowledged = new Decision<>(observed, blocked); pending = null;
        return acknowledged;
    }
    synchronized boolean currentDecision(Decision<C> decision) {
        return decision != null && acknowledged == decision && decision.blocked
                && current(decision.observation.configuration, decision.observation.epoch);
    }
    synchronized boolean homeRequested(Decision<C> decision) {
        // The native call may synchronously cause a source/Stop callback before returning. Its
        // old result cannot mutate the successor's handoff and is not a persistence failure.
        if (!currentDecision(decision)) return false;
        handoff.homeRequested(); usage.reset(); return true;
    }
    synchronized boolean eligible(RuntimeLease.Identity expected, C image, String packageName,
            int windowId, long monotonic, OwnerObservation calendar) {
        Decision<C> decision = acknowledged;
        if (!open || !owner.equals(expected) || image != configuration || decision == null || decision.blocked) return false;
        Observation<C> observed = decision.observation;
        return current(image, observed.epoch) && observed.protectionEligible && !observed.packageName.isEmpty()
                && observed.packageName.equals(packageName) && observed.windowId == windowId
                && fresh(observed, monotonic, calendar);
    }
    static boolean fresh(Observation<?> observed, long monotonic, OwnerObservation calendar) {
        return monotonic >= observed.monotonic
                && monotonic - observed.monotonic <= ForegroundUsageSession.MAX_OBSERVATION_GAP_MILLIS
                && calendar.at >= observed.calendar.at && calendar.zone.equals(observed.calendar.zone)
                && calendar.day().equals(observed.calendar.day());
    }
    synchronized void seal() { open = false; invalidate(true); }
}
