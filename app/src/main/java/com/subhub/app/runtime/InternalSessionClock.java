package com.subhub.app.runtime;

import com.subhub.app.commitment.PactDuration;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.stats.OwnerObservation;
import java.security.SecureRandom;
import java.util.*;

/**
 * Pure owner-local clock data. A decoded LIVE record is interrupted evidence, never a lease or
 * permission to resume. Only the exact runtime binding may publish a live Snapshot after ACK.
 * Durations and granted pauses use monotonic time in that one process. Wall/calendar values are
 * event evidence only, so wall rollback, timezone changes and delayed delivery cannot extend them.
 */
public final class InternalSessionClock {
    public static final String NAMESPACE = "internal_session_clock_v1";
    public static final long PERMANENT = -1, RANDOM = -2;
    private static final int RETAINED_PAUSES = 200;
    public enum Phase { LIVE, STOPPED, RECOVERED }
    public enum Status { PREPARED, ACTIVE, SIMULATED_PAUSE, CONFIGURATION_PENDING, PAUSE_END_PENDING, EXPIRED, STOPPING, STOPPED, RECOVERY_REQUIRED }
    private InternalSessionClock() { }

    public static final class Sample {
        public final OwnerObservation calendar;
        public final long elapsed;
        public Sample(OwnerObservation calendar, long elapsed) {
            this.calendar = Objects.requireNonNull(calendar);
            if (elapsed < 0) throw new IllegalArgumentException("Negative monotonic clock");
            this.elapsed = elapsed;
        }
    }
    /** The same selection contract as ServiceDurationView; selected once at STARTUP. */
    public static final class Selection {
        public final long mode, minimum, maximum;
        public final boolean hidden;
        public Selection(long mode, long minimum, long maximum, boolean hidden) {
            PactDuration.validate(minimum, maximum);
            if (mode != PERMANENT && mode != RANDOM) PactDuration.validate(mode, mode);
            this.mode = mode; this.minimum = minimum; this.maximum = maximum; this.hidden = hidden;
        }
        long choose(SecureRandom random) {
            return mode == RANDOM ? PactDuration.choose(minimum, maximum, Objects.requireNonNull(random)) : mode;
        }
    }
    /** Immutable values from an acknowledged config, not a mutable rule provider or payment API. */
    public static final class Rules {
        public final long revision;
        public final boolean penanceEnabled, pauseEnabled, permanentPauseEligible;
        public final int cents, minutes;
        public final String currency;
        public Rules(long revision, boolean penanceEnabled, boolean pauseEnabled,
                boolean permanentPauseEligible, int cents, int minutes, String currency) {
            if (revision < 0 || cents < 50 || cents > 100_000 || minutes < 1 || minutes > 1440)
                throw new IllegalArgumentException("Invalid internal pause rule");
            this.revision = revision; this.penanceEnabled = penanceEnabled; this.pauseEnabled = pauseEnabled;
            this.permanentPauseEligible = permanentPauseEligible; this.cents = cents; this.minutes = minutes;
            this.currency = WalletCurrency.requireSupported(currency);
        }
    }
    /** Synthetic assessed receipt only: never a Wallet settlement, paid row, balance or transfer. */
    public static final class Pause {
        public final String id, session, currency;
        public final long sequence, configurationRevision, duration;
        public final int cents;
        public final Sample started;
        private Pause(String id, String session, long sequence, long revision, Sample started,
                long duration, int cents, String currency) {
            this.id = RuntimeOrigin.uuid(id); this.session = RuntimeOrigin.uuid(session);
            if (sequence <= 0 || revision < 0 || duration < 60_000 || duration > 86_400_000
                    || cents < 50 || cents > 100_000) throw new IllegalArgumentException("Invalid simulated pause receipt");
            this.sequence = sequence; configurationRevision = revision; this.started = Objects.requireNonNull(started);
            this.duration = duration; this.cents = cents; this.currency = WalletCurrency.requireSupported(currency);
            Math.addExact(started.elapsed, duration);
        }
        String encoded() {
            return String.join("\t", id, session, Long.toString(sequence), Long.toString(configurationRevision),
                    Long.toString(started.calendar.at), started.calendar.zone, Long.toString(started.elapsed),
                    Long.toString(duration), Integer.toString(cents), currency);
        }
        static Pause decode(String encoded) {
            String[] v = encoded.split("\t", -1);
            if (v.length != 10) throw new IllegalArgumentException("Invalid simulated pause receipt");
            return new Pause(v[0], v[1], Long.parseLong(v[2]), Long.parseLong(v[3]),
                    new Sample(new OwnerObservation(Long.parseLong(v[4]), v[5]), Long.parseLong(v[6])),
                    Long.parseLong(v[7]), Integer.parseInt(v[8]), v[9]);
        }
    }
    public static final class State {
        public final String session, generation;
        public final Phase phase;
        public final Sample started;
        public final Selection selection;
        public final long selectedDuration, pauseSequence, endedAt;
        public final String endedZone;
        public final Pause pause;
        public final List<Pause> recentPauses;
        public final Map<String, Long> simulatedTotals;
        private State(String session, String generation, Phase phase, Sample started, Selection selection,
                long selectedDuration, Pause pause, long pauseSequence, List<Pause> receipts,
                Map<String, Long> totals, long endedAt, String endedZone) {
            this.session = RuntimeOrigin.uuid(session); this.generation = RuntimeOrigin.key(generation);
            this.phase = Objects.requireNonNull(phase); this.started = Objects.requireNonNull(started);
            this.selection = Objects.requireNonNull(selection);
            if (selection.mode == RANDOM) {
                if (selectedDuration < selection.minimum || selectedDuration > selection.maximum)
                    throw new IllegalArgumentException("Selected duration outside its original range");
            } else if (selectedDuration != selection.mode) throw new IllegalArgumentException("Changed selected duration");
            if (selectedDuration != PERMANENT) Math.addExact(started.elapsed, selectedDuration);
            if (pauseSequence < 0 || receipts.size() > RETAINED_PAUSES) throw new IllegalArgumentException("Invalid pause history");
            this.selectedDuration = selectedDuration; this.pauseSequence = pauseSequence;
            long previous = Math.max(0, pauseSequence - RETAINED_PAUSES);
            Set<String> ids = new HashSet<>(); Map<String, Long> retained = new TreeMap<>();
            for (Pause receipt : receipts) {
                if (receipt.sequence != ++previous || !ids.add(receipt.id)) throw new IllegalArgumentException("Invalid pause receipt order");
                retained.put(receipt.currency, Math.addExact(retained.getOrDefault(receipt.currency, 0L), receipt.cents));
            }
            if (previous != pauseSequence || receipts.size() != Math.min(pauseSequence, RETAINED_PAUSES))
                throw new IllegalArgumentException("Missing simulated pause receipts");
            Map<String, Long> copied = new TreeMap<>();
            for (Map.Entry<String, Long> total : totals.entrySet()) {
                WalletCurrency.requireSupported(total.getKey());
                if (total.getValue() == null || total.getValue() < retained.getOrDefault(total.getKey(), 0L) || total.getValue() < 0)
                    throw new IllegalArgumentException("Invalid synthetic total");
                copied.put(total.getKey(), total.getValue());
            }
            if (!copied.keySet().containsAll(retained.keySet())) throw new IllegalArgumentException("Missing synthetic total");
            if (pause != null && (phase != Phase.LIVE || !session.equals(pause.session)
                    || pause.sequence != pauseSequence || receipts.isEmpty()
                    || !pause.encoded().equals(receipts.get(receipts.size() - 1).encoded())
                    || pause.started.elapsed < started.elapsed)) throw new IllegalArgumentException("Invalid active simulated pause");
            this.pause = pause; recentPauses = Collections.unmodifiableList(new ArrayList<>(receipts));
            simulatedTotals = Collections.unmodifiableMap(copied); this.endedAt = endedAt;
            this.endedZone = Objects.requireNonNull(endedZone);
            if (phase == Phase.LIVE) {
                if (endedAt != 0 || !endedZone.isEmpty()) throw new IllegalArgumentException("Live clock has closure evidence");
            } else java.time.ZoneId.of(endedZone);
        }
        public boolean requiresRecovery() { return phase == Phase.LIVE; }
        public void requireOwner(RuntimeLease.Identity owner) {
            if (owner == null || owner.target.kind != RuntimeTarget.Kind.INTERNAL || !session.equals(owner.session)
                    || !generation.equals(owner.profileGeneration)) throw new SecurityException("Different session clock owner");
        }
    }
    /** No executable provider, mutable source, lease or callback is exposed to UI. */
    public static final class Snapshot {
        public final State state;
        public final Status status;
        public final long remainingMillis, pauseRemainingMillis, configurationRevision, observedElapsed;
        public final boolean protectionEligible, canSimulatePause, simulatedNonfinancial = true;
        private Snapshot(State state, Status status, long remaining, long pauseRemaining, long revision,
                long observedElapsed, boolean protection, boolean canPause) {
            this.state = state; this.status = status; remainingMillis = remaining; pauseRemainingMillis = pauseRemaining;
            configurationRevision = revision; this.observedElapsed = observedElapsed;
            protectionEligible = protection; canSimulatePause = canPause;
        }
    }
    public static State begin(State previous, RuntimeLease.Identity owner, Sample started,
            Selection selection, SecureRandom random) {
        if (owner.target.kind != RuntimeTarget.Kind.INTERNAL) throw new SecurityException("INTERNAL clock required");
        if (previous != null && (previous.phase == Phase.LIVE || previous.session.equals(owner.session)))
            throw new IllegalStateException("Previous clock requires recovery or session identity was reused");
        return new State(owner.session, owner.profileGeneration, Phase.LIVE, started, selection, selection.choose(random),
                null, previous == null ? 0 : previous.pauseSequence,
                previous == null ? List.of() : previous.recentPauses,
                previous == null ? Map.of() : previous.simulatedTotals, 0, "");
    }
    public static Snapshot observe(State state) {
        Objects.requireNonNull(state);
        return new Snapshot(state, state.phase == Phase.LIVE ? Status.RECOVERY_REQUIRED : Status.STOPPED,
                0, 0, -1, -1, false, false);
    }
    /** In-process fence only; does not claim that durable cleanup has already completed. */
    public static Snapshot stopping(State state) {
        return new Snapshot(Objects.requireNonNull(state), Status.STOPPING, 0, 0, -1, -1, false, false);
    }
    /**
     * Read-only projection of the last acknowledged in-process receipt. Fresh monotonic time may
     * withdraw eligibility, never grant it. Pending config/expired-pause flags wait for the owning
     * writer's ACK; querying a countdown cannot retire a grant, resume protection, or write data.
     * A pending revision is the coherently observed runtime revision, or -1 while it is changing.
     */
    public static Snapshot project(Snapshot acknowledged, long elapsed, long runtimeRevision,
            boolean configurationCurrent) {
        Objects.requireNonNull(acknowledged);
        State state = acknowledged.state;
        if (state.phase != Phase.LIVE) return observe(state);
        if (elapsed < acknowledged.observedElapsed || elapsed < state.started.elapsed
                || state.pause != null && elapsed < state.pause.started.elapsed)
            return observe(state); // Clock regression is not fresh live evidence.
        long remaining = remaining(state, elapsed), paused = pauseRemaining(state, elapsed);
        boolean due = state.selectedDuration != PERMANENT && remaining == 0;
        Status status = due ? Status.EXPIRED : !configurationCurrent ? Status.CONFIGURATION_PENDING
                : state.pause != null && paused == 0 ? Status.PAUSE_END_PENDING : acknowledged.status;
        boolean current = !due && configurationCurrent && (state.pause == null || paused > 0);
        return new Snapshot(state, status, remaining, paused, runtimeRevision, elapsed,
                current && acknowledged.protectionEligible, current && acknowledged.canSimulatePause);
    }
    public static Snapshot live(State state, RuntimeLease.Identity owner, Sample now, Rules rules, boolean active) {
        state.requireOwner(owner); Objects.requireNonNull(rules);
        if (state.phase != Phase.LIVE) return observe(state);
        long remaining = remaining(state, now), paused = pauseRemaining(state, now);
        boolean due = state.selectedDuration != PERMANENT && remaining == 0;
        Status status = due ? Status.EXPIRED : !active ? Status.PREPARED : paused > 0 ? Status.SIMULATED_PAUSE : Status.ACTIVE;
        boolean protection = active && !due && paused == 0;
        return new Snapshot(state, status, remaining, paused, rules.revision, now.elapsed, protection,
                protection && rules.penanceEnabled && rules.pauseEnabled
                        && (state.selectedDuration != PERMANENT || rules.permanentPauseEligible));
    }
    public static State tick(State state, RuntimeLease.Identity owner, Sample now, Rules rules) {
        state.requireOwner(owner); remaining(state, now);
        if (state.phase != Phase.LIVE) throw new IllegalStateException("Session clock is closed");
        return state.pause != null && (pauseRemaining(state, now) == 0 || !rules.penanceEnabled)
                ? copy(state, null, state.pauseSequence, state.recentPauses, state.simulatedTotals, state.phase, 0, "") : state;
    }
    /** Optimistic sequence fences duplicate/stale taps even when older receipts have rolled out. */
    public static State pause(State state, RuntimeLease.Identity owner, Sample observed, Rules rules,
            long expectedSequence, String requestId) {
        state.requireOwner(owner); RuntimeOrigin.uuid(requestId);
        if (expectedSequence != state.pauseSequence || !live(state, owner, observed, rules, true).canSimulatePause)
            throw new IllegalStateException("Simulated pause is no longer available");
        Pause grant = new Pause(requestId, state.session, Math.addExact(state.pauseSequence, 1), rules.revision,
                observed, Math.multiplyExact(rules.minutes, 60_000L), rules.cents, rules.currency);
        for (Pause retained : state.recentPauses) if (retained.id.equals(requestId))
            throw new IllegalArgumentException("Simulated pause request reused");
        List<Pause> receipts = new ArrayList<>(state.recentPauses); receipts.add(grant);
        if (receipts.size() > RETAINED_PAUSES) receipts.remove(0);
        Map<String, Long> totals = new TreeMap<>(state.simulatedTotals);
        totals.put(grant.currency, Math.addExact(totals.getOrDefault(grant.currency, 0L), grant.cents));
        return copy(state, grant, grant.sequence, receipts, totals, state.phase, 0, "");
    }
    public static State finish(State state, RuntimeLease.Identity owner, OwnerObservation firstFence) {
        state.requireOwner(owner);
        if (state.phase != Phase.LIVE) throw new IllegalStateException("Clock closure already recorded");
        return copy(state, null, state.pauseSequence, state.recentPauses, state.simulatedTotals,
                Phase.STOPPED, firstFence.at, firstFence.zone);
    }
    /** Explicit controller data-recovery lane only. This creates no runtime and never rearms. */
    public static State recover(State state, String expectedSession, OwnerObservation observed) {
        if (state.phase != Phase.LIVE || !state.session.equals(expectedSession)) throw new SecurityException("Different interrupted session");
        return copy(state, null, state.pauseSequence, state.recentPauses, state.simulatedTotals,
                Phase.RECOVERED, observed.at, observed.zone);
    }
    private static State copy(State s, Pause pause, long sequence, List<Pause> receipts, Map<String, Long> totals,
            Phase phase, long at, String zone) {
        return new State(s.session, s.generation, phase, s.started, s.selection, s.selectedDuration,
                pause, sequence, receipts, totals, at, zone);
    }
    private static long remaining(State state, Sample now) { return remaining(state, now.elapsed); }
    private static long remaining(State state, long elapsed) {
        if (elapsed < state.started.elapsed) throw new IllegalStateException("Monotonic clock regressed");
        return state.selectedDuration == PERMANENT ? PERMANENT
                : Math.max(0, state.selectedDuration - (elapsed - state.started.elapsed));
    }
    private static long pauseRemaining(State state, Sample now) { return pauseRemaining(state, now.elapsed); }
    private static long pauseRemaining(State state, long elapsed) {
        if (state.pause == null) return 0;
        if (elapsed < state.pause.started.elapsed) throw new IllegalStateException("Pause clock regressed");
        return Math.max(0, state.pause.duration - (elapsed - state.pause.started.elapsed));
    }
    public static Map<String, Object> encode(State s) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("version", 1); fields.put("session", s.session); fields.put("generation", s.generation);
        fields.put("phase", s.phase.name()); fields.put("started_at", s.started.calendar.at);
        fields.put("started_zone", s.started.calendar.zone); fields.put("started_elapsed", s.started.elapsed);
        fields.put("duration_mode", s.selection.mode); fields.put("minimum", s.selection.minimum);
        fields.put("maximum", s.selection.maximum); fields.put("hidden", s.selection.hidden);
        fields.put("selected_duration", s.selectedDuration); fields.put("pause_sequence", s.pauseSequence);
        fields.put("active_pause", s.pause == null ? "" : s.pause.encoded());
        Set<String> receipts = new TreeSet<>(); for (Pause pause : s.recentPauses) receipts.add(pause.encoded());
        fields.put("simulated_receipts", Collections.unmodifiableSet(receipts));
        fields.put("simulated_eur_cents", s.simulatedTotals.getOrDefault("EUR", 0L));
        fields.put("simulated_usd_cents", s.simulatedTotals.getOrDefault("USD", 0L));
        fields.put("ended_at", s.endedAt); fields.put("ended_zone", s.endedZone);
        return Collections.unmodifiableMap(fields);
    }
    public static State decode(Map<String, ?> fields) {
        Objects.requireNonNull(fields); if (fields.isEmpty()) return null;
        if (!fields.keySet().equals(Set.of("version", "session", "generation", "phase", "started_at", "started_zone",
                "started_elapsed", "duration_mode", "minimum", "maximum", "hidden", "selected_duration", "pause_sequence",
                "active_pause", "simulated_receipts", "simulated_eur_cents", "simulated_usd_cents", "ended_at", "ended_zone"))
                || !Integer.valueOf(1).equals(fields.get("version"))) throw new IllegalArgumentException("Unknown clock schema");
        List<Pause> receipts = new ArrayList<>(); Object raw = fields.get("simulated_receipts");
        if (!(raw instanceof Set<?>) || ((Set<?>) raw).size() > RETAINED_PAUSES) throw new IllegalArgumentException("Invalid simulated receipts");
        for (Object value : (Set<?>) raw) { if (!(value instanceof String)) throw new IllegalArgumentException("Invalid receipt type"); receipts.add(Pause.decode((String) value)); }
        receipts.sort(Comparator.comparingLong(p -> p.sequence));
        String active = string(fields, "active_pause");
        return new State(string(fields, "session"), string(fields, "generation"), Phase.valueOf(string(fields, "phase")),
                new Sample(new OwnerObservation(number(fields, "started_at"), string(fields, "started_zone")), number(fields, "started_elapsed")),
                new Selection(number(fields, "duration_mode"), number(fields, "minimum"), number(fields, "maximum"), typed(fields, "hidden", Boolean.class)),
                number(fields, "selected_duration"), active.isEmpty() ? null : Pause.decode(active), number(fields, "pause_sequence"), receipts,
                Map.of("EUR", number(fields, "simulated_eur_cents"), "USD", number(fields, "simulated_usd_cents")),
                number(fields, "ended_at"), string(fields, "ended_zone"));
    }
    private static String string(Map<String, ?> fields, String key) { return typed(fields, key, String.class); }
    private static long number(Map<String, ?> fields, String key) { return typed(fields, key, Long.class); }
    private static <T> T typed(Map<String, ?> fields, String key, Class<T> type) {
        Object value = fields.get(key); if (!type.isInstance(value)) throw new IllegalArgumentException("Invalid clock field: " + key);
        return type.cast(value);
    }
}
