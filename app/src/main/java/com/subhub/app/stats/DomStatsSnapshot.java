package com.subhub.app.stats;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Totals over current relationships, never a sum of sync receipts or daily/lifetime copies. */
public final class DomStatsSnapshot {
    public static final long STATS_FRESH_MILLIS = 30 * 60_000L;
    public static final long RUNTIME_FRESH_MILLIS = 90_000L;
    public final int subs, totalsKnown, totalsFresh, walletKnown, activeKnown, activeSubs;
    public final int hardcoreKnown, hardcoreSubs;
    public final long sessionSeconds, sessions;
    public final Map<String, Long> paidCents, assessedCents;

    private DomStatsSnapshot(Collection<Sub> observations, long now) {
        Map<String, Sub> unique = new TreeMap<>();
        for (Sub value : observations) {
            Sub previous = unique.get(value.identity);
            if (previous == null || value.compareTo(previous) > 0) unique.put(value.identity, value);
        }
        int known = 0, fresh = 0, wallets = 0, live = 0, active = 0, hardcore = 0, hardened = 0;
        long seconds = 0, count = 0;
        Map<String, Long> paid = currencies(), assessed = currencies();
        for (Sub sub : unique.values()) {
            if (sub.totals != null) {
                known++;
                if (fresh(sub.statsConfirmedAt, now, STATS_FRESH_MILLIS)) fresh++;
                seconds = add(seconds, sub.totals.sessionSeconds);
                count = add(count, sub.totals.sessions);
                if (sub.runtime != null && Boolean.TRUE.equals(sub.runtime.walletAllowed)) {
                    wallets++;
                    for (String currency : paid.keySet()) {
                        paid.put(currency, add(paid.get(currency), sub.totals.paid.get(currency)));
                        assessed.put(currency, add(assessed.get(currency), sub.totals.assessed.get(currency)));
                    }
                }
            }
            if (sub.runtime != null && fresh(sub.runtimeConfirmedAt, now, RUNTIME_FRESH_MILLIS)) {
                live++;
                if (sub.runtime.serviceActive) active++;
                if (sub.runtime.hardcoreEnabled != null) {
                    hardcore++;
                    if (sub.runtime.hardcoreEnabled) hardened++;
                }
            }
        }
        subs = unique.size(); totalsKnown = known; totalsFresh = fresh; walletKnown = wallets;
        activeKnown = live; activeSubs = active; hardcoreKnown = hardcore; hardcoreSubs = hardened;
        sessionSeconds = seconds; sessions = count;
        paidCents = Collections.unmodifiableMap(paid);
        assessedCents = Collections.unmodifiableMap(assessed);
    }

    public static DomStatsSnapshot aggregate(Collection<Sub> values, long now) {
        return new DomStatsSnapshot(values, now);
    }

    public static DomStatsSnapshot empty() {
        return aggregate(Collections.emptyList(), 0);
    }

    public String leadingPaidCurrency() {
        return paidCents.get("USD") > paidCents.get("EUR") ? "USD" : "EUR";
    }

    public long leadingPaidCents() { return paidCents.get(leadingPaidCurrency()); }
    public boolean hasTotals() { return subs == 0 || totalsKnown > 0; }
    public boolean hasWalletTotals() { return subs == 0 || walletKnown > 0; }
    public boolean hasHardcoreStatus() { return subs == 0 || hardcoreKnown > 0; }

    static boolean fresh(long confirmed, long now, long lifetime) {
        return confirmed > 0 && confirmed <= now && now - confirmed <= lifetime;
    }

    static long add(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative aggregate input");
        return value;
    }

    private static Map<String, Long> currencies() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("EUR", 0L); result.put("USD", 0L); return result;
    }

    public static final class Totals {
        public final long sessionSeconds, sessions;
        public final Map<String, Long> paid, assessed;
        public Totals(long sessionSeconds, long sessions, long paidEur, long paidUsd,
                long assessedEur, long assessedUsd) {
            this.sessionSeconds = nonnegative(sessionSeconds); this.sessions = nonnegative(sessions);
            Map<String, Long> payments = currencies(), assessments = currencies();
            payments.put("EUR", nonnegative(paidEur)); payments.put("USD", nonnegative(paidUsd));
            assessments.put("EUR", nonnegative(assessedEur)); assessments.put("USD", nonnegative(assessedUsd));
            paid = Collections.unmodifiableMap(payments); assessed = Collections.unmodifiableMap(assessments);
        }
    }

    public static final class Observation {
        public final boolean serviceActive;
        public final Boolean hardcoreEnabled, walletAllowed;
        public Observation(boolean active, Boolean hardcore, Boolean walletAllowed) {
            serviceActive = active; hardcoreEnabled = hardcore; this.walletAllowed = walletAllowed;
        }
    }

    /** One authenticated active Sub relationship, keyed by the peer's durable identity. */
    public static final class Sub implements Comparable<Sub> {
        public final String identity, generation;
        public final long relationshipOrder, statsConfirmedAt, runtimeConfirmedAt;
        public final Totals totals;
        public final Observation runtime;
        public Sub(String identity, String generation, long relationshipOrder,
                long statsConfirmedAt, long runtimeConfirmedAt, Totals totals, Observation runtime) {
            if (identity == null || identity.isEmpty() || generation == null || generation.isEmpty())
                throw new IllegalArgumentException("Missing Sub identity");
            this.identity = identity; this.generation = generation;
            this.relationshipOrder = nonnegative(relationshipOrder);
            this.statsConfirmedAt = nonnegative(statsConfirmedAt);
            this.runtimeConfirmedAt = nonnegative(runtimeConfirmedAt);
            this.totals = totals; this.runtime = runtime;
        }
        @Override public int compareTo(Sub other) {
            int ordered = Long.compare(relationshipOrder, other.relationshipOrder);
            if (ordered != 0) return ordered;
            ordered = generation.compareTo(other.generation);
            if (ordered != 0) return ordered;
            ordered = Long.compare(statsConfirmedAt, other.statsConfirmedAt);
            return ordered != 0 ? ordered : Long.compare(runtimeConfirmedAt, other.runtimeConfirmedAt);
        }
    }
}
