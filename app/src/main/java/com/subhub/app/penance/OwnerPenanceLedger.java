package com.subhub.app.penance;

import android.content.SharedPreferences;
import android.os.Looper;
import com.subhub.app.stats.StatsRepository;
import com.subhub.app.stats.OwnerObservation;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Assessed internal balance only. No payment credentials, checkout, scheduling or remote effects. */
public final class OwnerPenanceLedger {
    public enum Status { ACKNOWLEDGED, REJECTED }
    public static final class Result {
        public final Status status;
        public final int chargedCents, batchRemainder;
        private Result(Status status, int cents, int remainder) {
            this.status = status; chargedCents = cents; batchRemainder = remainder;
        }
    }
    public interface Store {
        SharedPreferences ledger();
        void requireSessionWrite();
        /** Atomic shared transaction with the supplied StatsRepository counters, ACK before return. */
        <T> T transaction(Supplier<T> action);
    }
    private static final String EVENTS = "assessed_events_v1", REMAINDER = "detection_remainder_v1";
    private static final int MAX_EVENTS = 200;
    private static final String ARCHIVE = "archived_event:", EVENT_ZONE = "event_zone:";
    private final Store store;
    private final StatsRepository stats;
    private final Supplier<PenanceRuleSettings> configuration;
    private final BooleanSupplier censorEnabled;

    public OwnerPenanceLedger(Store store, StatsRepository stats,
            Supplier<PenanceRuleSettings> liveConfiguration, BooleanSupplier censorEnabled) {
        this.store = Objects.requireNonNull(store); this.stats = Objects.requireNonNull(stats);
        this.configuration = Objects.requireNonNull(liveConfiguration);
        this.censorEnabled = Objects.requireNonNull(censorEnabled);
    }
    public int getDwellSeconds() { return configuration.get().getDwellSeconds(); }
    public Result recordSourceDetectionResult(int count, long now, StatsRepository.SessionStamp expected) {
        return recordSourceDetectionResult(count, OwnerObservation.capture(now), expected);
    }
    public Result recordSourceDwellResult(long now, StatsRepository.SessionStamp expected) {
        return recordSourceDwellResult(1, OwnerObservation.capture(now), expected);
    }
    public Result recordSourceDwellResult(int count, long now, StatsRepository.SessionStamp expected) {
        return recordSourceDwellResult(count, OwnerObservation.capture(now), expected);
    }
    public Result recordSourceDetectionResult(int count, OwnerObservation observed, StatsRepository.SessionStamp expected) {
        return record(PenanceInfraction.NEW_DETECTION, count, Objects.requireNonNull(observed), expected);
    }
    public Result recordSourceDwellResult(OwnerObservation observed, StatsRepository.SessionStamp expected) {
        return recordSourceDwellResult(1, observed, expected);
    }
    public Result recordSourceDwellResult(int count, OwnerObservation observed, StatsRepository.SessionStamp expected) {
        return record(PenanceInfraction.CENSORED_DWELL, count, Objects.requireNonNull(observed), expected);
    }
    /** One real admitted censored tap. Geometry, event debounce and configuration fencing belong to the renderer. */
    public Result recordSourceCensoredTapResult(OwnerObservation observed, StatsRepository.SessionStamp expected) {
        return record(PenanceInfraction.CENSORED_TAP, 1, Objects.requireNonNull(observed), expected);
    }
    /** Task data authority is separate from protection. The Store guard admits this owned task worker. */
    public Result recordTaskFailure(String occurrence, long now, BooleanSupplier tasksEnabled) {
        return recordTaskFailure(occurrence, OwnerObservation.capture(now), tasksEnabled);
    }
    public Result recordTaskFailure(String occurrence, OwnerObservation observed, BooleanSupplier tasksEnabled) {
        Objects.requireNonNull(observed); long now = observed.at;
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Owner task assessment must be off main");
        if (occurrence == null || occurrence.isEmpty() || occurrence.length() > 512 || now <= 0)
            throw new IllegalArgumentException("Invalid owned task failure");
        Objects.requireNonNull(tasksEnabled);
        String id = UUID.nameUUIDFromBytes(occurrence.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String receipt = "task_failure:" + id;
        return store.transaction(() -> {
            SharedPreferences ledger = store.ledger();
            if (ledger.contains(receipt)) return new Result(Status.ACKNOWLEDGED, 0, ledger.getInt(REMAINDER, 0));
            PenanceRuleSettings rules = configuration.get();
            String currency = WalletCurrency.requireSupported(rules.getCurrency());
            List<PenanceEvent> events = decode(ledger.getString(EVENTS, ""));
            List<PenanceEvent> inCurrency = new ArrayList<>();
            for (PenanceEvent event : retained(ledger)) if (currency.equals(event.getCurrency())) inCurrency.add(event);
            int amount = !tasksEnabled.getAsBoolean() || !rules.isEnabled()
                    || !rules.isInfractionEnabled(PenanceInfraction.TASK_FAILURE) ? 0
                    : PenancePolicy.boundedCharge(inCurrency, now, 1, rules.getInfractionCents(PenanceInfraction.TASK_FAILURE),
                            rules.getDailyCapCents(), rules.getWeeklyCapCents(), ZoneId.of(observed.zone));
            if (amount > 0) events.add(new PenanceEvent(id, now,
                    Math.addExact(now, PenancePolicy.clampMercyMinutes(rules.getMercyMinutes()) * 60_000L), amount, 1,
                    PenanceInfraction.TASK_FAILURE, PenanceEvent.Status.OPEN, "", currency));
            SharedPreferences.Editor edit = ledger.edit().putBoolean(receipt, true);
            if (amount > 0) edit.putString(EVENT_ZONE + id, observed.zone);
            retainRecent(ledger, edit, events);
            if (!edit.commit())
                throw new IllegalStateException("Owned task assessment not acknowledged");
            if (amount > 0) stats.recordRequestTributeReceipt("internal_task:" + id, amount, currency, null, observed);
            return new Result(Status.ACKNOWLEDGED, amount, ledger.getInt(REMAINDER, 0));
        });
    }

    private Result record(PenanceInfraction infraction, int count, OwnerObservation observed, StatsRepository.SessionStamp expected) {
        long now = observed.at;
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Owner ledger writes must be off main");
        if (count <= 0 || now <= 0 || !current(expected)) return new Result(Status.REJECTED, 0, 0);
        return store.transaction(() -> {
            store.requireSessionWrite();
            if (!current(expected)) return new Result(Status.REJECTED, 0, 0);
            PenanceRuleSettings rules = configuration.get();
            SharedPreferences ledger = store.ledger();
            int oldRemainder = ledger.getInt(REMAINDER, 0);
            if (oldRemainder < 0 || oldRemainder >= PenancePolicy.MAX_DETECTION_BATCH)
                throw new IllegalStateException("Corrupt internal detection remainder");
            if (!censorEnabled.getAsBoolean() || !rules.isEnabled() || !rules.isInfractionEnabled(infraction))
                return new Result(Status.REJECTED, 0, oldRemainder);
            int remainder = oldRemainder;
            long billable = count;
            if (infraction == PenanceInfraction.NEW_DETECTION) {
                int batch = PenancePolicy.clampDetectionBatch(rules.getDetectionBatch());
                long accumulated = (long) oldRemainder + count;
                billable = accumulated / batch; remainder = (int) (accumulated % batch);
            }
            List<PenanceEvent> events = decode(ledger.getString(EVENTS, ""));
            List<PenanceEvent> currencyEvents = new ArrayList<>();
            String currency = WalletCurrency.requireSupported(rules.getCurrency());
            for (PenanceEvent event : retained(ledger)) if (currency.equals(event.getCurrency())) currencyEvents.add(event);
            int amount = PenancePolicy.boundedCharge(currencyEvents, now,
                    (int) Math.min(Integer.MAX_VALUE, billable), rules.getInfractionCents(infraction),
                    rules.getDailyCapCents(), rules.getWeeklyCapCents(), ZoneId.of(observed.zone));
            SharedPreferences.Editor edit = ledger.edit().putInt(REMAINDER, remainder);
            if (amount > 0) {
                long mercy = Math.addExact(now, PenancePolicy.clampMercyMinutes(rules.getMercyMinutes()) * 60_000L);
                String id = UUID.randomUUID().toString();
                events.add(new PenanceEvent(id, now, mercy, amount,
                        (int) Math.min(Integer.MAX_VALUE, billable), infraction, PenanceEvent.Status.OPEN, "", currency));
                edit.putString(EVENT_ZONE + id, observed.zone);
                retainRecent(ledger, edit, events);
            }
            if (!current(expected)) return new Result(Status.REJECTED, 0, oldRemainder);
            if (!edit.commit()) throw new IllegalStateException("Internal ledger not acknowledged");
            if (amount > 0) stats.recordSourceTribute(expected, amount, currency, observed);
            // Returning from transaction, not its nested editor, is the durability acknowledgment.
            return new Result(Status.ACKNOWLEDGED, amount, remainder);
        });
    }
    private boolean current(StatsRepository.SessionStamp expected) {
        return expected != null && !expected.isDeviceSession() && stats.ownsSessionStamp(expected)
                && expected == stats.observedSessionStamp() && expected.isCurrent();
    }
    public List<PenanceEvent> events() {
        return Collections.unmodifiableList(decode(store.ledger().getString(EVENTS, "")));
    }

    /** Full retained facts, including archived presentation rows. Still bounded by the typed record. */
    public List<PenanceEvent> retainedEvents() { return Collections.unmodifiableList(retained(store.ledger())); }
    private static List<PenanceEvent> retained(SharedPreferences ledger) {
        List<PenanceEvent> result = decode(ledger.getString(EVENTS, ""));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (PenanceEvent event : result) ids.add(event.getId());
        for (java.util.Map.Entry<String, ?> entry : ledger.getAll().entrySet()) if (entry.getKey().startsWith(ARCHIVE)) {
            if (!(entry.getValue() instanceof String)) throw new IllegalStateException("Corrupt assessment archive");
            List<PenanceEvent> one = decode((String) entry.getValue());
            if (one.size() != 1 || !entry.getKey().equals(ARCHIVE + one.get(0).getId()) || !ids.add(one.get(0).getId()))
                throw new IllegalStateException("Corrupt assessment archive identity");
            result.add(one.get(0));
        }
        result.sort(java.util.Comparator.comparingLong(PenanceEvent::getCreatedAtMillis).thenComparing(PenanceEvent::getId));
        return result;
    }
    private static void retainRecent(SharedPreferences ledger, SharedPreferences.Editor edit, List<PenanceEvent> events) {
        while (events.size() > MAX_EVENTS) {
            PenanceEvent old = events.remove(0); String key = ARCHIVE + old.getId();
            if (ledger.contains(key)) throw new IllegalStateException("Assessment was already archived");
            edit.putString(key, encode(Collections.singletonList(old)));
        }
        // Capacity/IO failures throw from the encompassing bounded typed transaction. They never
        // publish a no-charge task receipt or discard the exact archived/currency facts.
        edit.putString(EVENTS, encode(events));
    }

    static String encode(List<PenanceEvent> events) {
        List<String> rows = new ArrayList<>();
        for (PenanceEvent event : events) rows.add(String.join(",", event.getId(), Long.toString(event.getCreatedAtMillis()),
                Long.toString(event.getMercyEndsAtMillis()), Integer.toString(event.getAmountCents()),
                Integer.toString(event.getStrikeCount()), event.getInfraction().name(), event.getCurrency()));
        return String.join(";", rows);
    }
    static List<PenanceEvent> decode(String encoded) {
        if (encoded == null || encoded.length() > 100_000) throw new IllegalStateException("Corrupt internal ledger");
        List<PenanceEvent> events = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (encoded.isEmpty()) return events;
        try {
            for (String row : encoded.split(";", -1)) {
                String[] fields = row.split(",", -1);
                if (fields.length != 7 || !UUID.fromString(fields[0]).toString().equals(fields[0]) || !ids.add(fields[0]))
                    throw new IllegalArgumentException("Invalid internal event");
                long at = Long.parseLong(fields[1]), mercy = Long.parseLong(fields[2]);
                int cents = Integer.parseInt(fields[3]), strikes = Integer.parseInt(fields[4]);
                PenanceInfraction kind = PenanceInfraction.valueOf(fields[5]);
                if (at <= 0 || mercy < at || cents <= 0 || cents > PenancePolicy.MAX_DAILY_CENTS || strikes <= 0
                        || !(kind == PenanceInfraction.NEW_DETECTION || kind == PenanceInfraction.CENSORED_DWELL
                                || kind == PenanceInfraction.CENSORED_TAP || kind == PenanceInfraction.TASK_FAILURE))
                    throw new IllegalArgumentException("Invalid internal assessment");
                events.add(new PenanceEvent(fields[0], at, mercy, cents, strikes, kind, PenanceEvent.Status.OPEN, "", fields[6]));
                if (events.size() > MAX_EVENTS) throw new IllegalArgumentException("Oversized internal ledger");
            }
        } catch (RuntimeException corrupt) { throw new IllegalStateException("Corrupt internal assessed ledger", corrupt); }
        return events;
    }
}
