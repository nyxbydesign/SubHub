package com.subhub.app.stats;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Dated peer records are combined independently from lifetime summary counters. */
final class DomDailySnapshot {
    static final String[] METRICS = {
            "service_ms", "sessions", "assessed_EUR", "assessed_USD", "paid_EUR", "paid_USD"
    };
    final String since;
    final Map<String, Map<String, Long>> days;

    private DomDailySnapshot(String since, Map<String, Map<String, Long>> days) {
        this.since = since;
        this.days = Collections.unmodifiableMap(days);
    }

    static DomDailySnapshot aggregate(Collection<History> inputs, String first, String last) {
        LocalDate start = LocalDate.parse(first), end = LocalDate.parse(last);
        if (end.isBefore(start) || end.toEpochDay() - start.toEpochDay() > 366)
            throw new IllegalArgumentException("Invalid daily range");
        Map<String, History> peers = new TreeMap<>();
        for (History input : inputs) {
            History previous = peers.get(input.identity.identity);
            if (previous == null || input.identity.compareTo(previous.identity) > 0)
                peers.put(input.identity.identity, input);
        }
        String since = null;
        for (History peer : peers.values())
            for (String day : peer.days.keySet())
                if (since == null || day.compareTo(since) < 0) since = day;
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String day = date.toString();
            Map<String, Long> amounts = new LinkedHashMap<>();
            for (String metric : METRICS) {
                long total = 0;
                boolean known = true, included = peers.isEmpty();
                for (History peer : peers.values()) {
                    if (financial(metric) && !peer.walletAllowed) continue;
                    included = true;
                    Map<String, Long> values = peer.days.get(day);
                    if (values == null) {
                        known = false;
                        break;
                    }
                    long value = values.getOrDefault(metric, 0L);
                    if (value == -1) { known = false; break; }
                    if (value < 0) throw new IllegalArgumentException("Negative daily metric");
                    total = DomStatsSnapshot.add(total, value);
                }
                amounts.put(metric, known && included ? total : -1L);
            }
            result.put(day, Collections.unmodifiableMap(amounts));
        }
        return new DomDailySnapshot(since == null ? first : since, result);
    }

    private static boolean financial(String metric) {
        return metric.startsWith("paid_") || metric.startsWith("assessed_");
    }

    static final class History {
        final DomStatsSnapshot.Sub identity;
        final boolean walletAllowed;
        final Map<String, Map<String, Long>> days;

        History(DomStatsSnapshot.Sub identity, boolean walletAllowed,
                Map<String, Map<String, Long>> days) {
            this.identity = identity;
            this.walletAllowed = walletAllowed;
            this.days = days;
        }
    }
}
