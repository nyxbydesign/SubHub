package com.subhub.app.stats;

import android.content.SharedPreferences;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owner-local cumulative checkpoints. No database singleton, background timer or remote effects. */
final class OwnerRetainedHistory {
    private static final String[][] COUNTERS = {
        {"censors", "total_blocks_all_time"}, {"limits_ms", "limited_app_millis"},
        {"limit_stops", "limit_interventions"}, {"whispers", "subliminal_impressions"},
        {"popups", "popup_impressions"}, {"assessed_EUR", "tribute_cents"},
        {"assessed_USD", "tribute_cents_USD"}, {"tributes", "tribute_events"},
        {"sessions", "sessions_count"}, {"photos", "exported_images_count"}};
    private final SharedPreferences history;
    OwnerRetainedHistory(SharedPreferences history) { this.history = history; }

    /** Must run inside the same acknowledged owner transaction as the source delta. */
    void checkpoint(Map<String, ?> source, long now, boolean durations) {
        if (now <= 0) throw new IllegalArgumentException("Invalid history observation");
        long wall = number(source, DailyStatsStore.STAMP, now);
        String clock = text(source, DailyStatsStore.ZONE, ZoneId.systemDefault().getId());
        String date = Instant.ofEpochMilli(wall).atZone(ZoneId.of(clock)).toLocalDate().toString();
        SharedPreferences.Editor edit = history.edit();
        if (!history.contains("collection_started")) edit.putString("collection_started", date);
        for (String[] counter : COUNTERS) {
            String key = "counter:" + counter[0];
            long total = number(source, counter[1], 0), prior = history.getLong(key, 0);
            if (total < prior) throw new IllegalStateException("Owner counter regressed");
            if (total > prior) {
                String day = "day:" + date + ":" + counter[0];
                edit.putLong(day, Math.addExact(history.getLong(day, 0), total - prior));
            }
            edit.putLong(key, total);
        }
        for (StatsRepository.SessionEntry session : StatsRepository.parseSessionHistory(text(source, "session_history", "")))
            retain(edit, session, true);
        if (durations) {
            long start = number(source, "active_session_start_ms", 0);
            String id = text(source, "active_session_identity", "");
            if (start > 0 && !id.isEmpty()) {
                if (start > now) throw new IllegalStateException("Owner history clock moved before start");
                retain(edit, session(source, now), false);
            }
        }
        if (!edit.commit()) throw new IllegalStateException("Owner history not acknowledged");
    }

    private void retain(SharedPreferences.Editor edit, StatsRepository.SessionEntry session, boolean closed) {
        String id = session.identity();
        if (id.isEmpty() || session.zone().isEmpty()) throw new IllegalStateException("Owned session lacks provenance");
        String key = "session:" + id, encoded = encode(session), before = history.getString(key, null);
        if (before != null) {
            StatsRepository.SessionEntry old = decode(before);
            if (old.getStartMillis() != session.getStartMillis() || !old.zone().equals(session.zone()))
                throw new IllegalStateException("Owned session origin changed");
            if (history.getBoolean("closed:" + id, false)) {
                if (closed && !before.equals(encoded)) throw new IllegalStateException("Closed owner session changed");
                return;
            }
            if (old.getDurationSeconds() > session.getDurationSeconds()) {
                if (closed) throw new IllegalStateException("Owner stop predates acknowledged duration");
                return;
            }
        }
        edit.putString(key, encoded);
        if (closed) edit.putBoolean("closed:" + id, true);
    }

    static StatsRepository.SessionEntry session(Map<String, ?> source, long now) {
        long start = number(source, "active_session_start_ms", 0);
        return new StatsRepository.SessionEntry(start, (now - start) / 1000,
                Math.toIntExact(number(source, "active_session_blocks", 0)),
                number(source, "active_session_limited_app_millis", 0), number(source, "active_session_limit_interventions", 0),
                number(source, "active_session_tribute_events", 0), number(source, "active_session_tribute_cents", 0),
                number(source, "active_session_subliminals", 0), number(source, "active_session_popup_impressions", 0),
                number(source, "active_session_tamper_events", 0)).identified(text(source, "active_session_identity", ""),
                        text(source, StatsRepository.ACTIVE_SESSION_ZONE, ""));
    }

    static RetainedStatistics project(Map<String, ?> saved, long now) {
        Map<String, Map<String, Long>> days = new LinkedHashMap<>();
        for (Map.Entry<String, ?> value : saved.entrySet()) {
            String key = value.getKey();
            if (!key.startsWith("day:")) continue;
            String date = key.substring(4, 14), metric = key.substring(15);
            if (key.charAt(14) != ':' || !LocalDate.parse(date).toString().equals(date)
                    || !java.util.Arrays.asList(RetainedStatistics.METRICS).contains(metric))
                throw new IllegalStateException("Corrupt owned daily history");
            days.computeIfAbsent(date, unused -> new LinkedHashMap<>()).put(metric, number(saved, key, 0));
        }
        // A backward wall adjustment cannot erase a duration already observed and acknowledged.
        // This is retained data, not permission to resume a session or count elapsed downtime.
        long projectionAt = now;
        for (Map.Entry<String, ?> value : saved.entrySet()) if (value.getKey().startsWith("session:")) {
            if (!(value.getValue() instanceof String)) throw new IllegalStateException("Corrupt owned session history");
            StatsRepository.SessionEntry known = decode((String) value.getValue());
            projectionAt = Math.max(projectionAt, Math.addExact(known.getStartMillis(), Math.multiplyExact(known.getDurationSeconds(), 1000)));
        }
        RetainedStatistics result = new RetainedStatistics(ZoneId.systemDefault(), projectionAt, days, false);
        result.legacyServiceDates(Collections.emptySet()); // This store has always had timestamped origins.
        for (Map.Entry<String, ?> value : saved.entrySet()) if (value.getKey().startsWith("session:")) {
            if (!(value.getValue() instanceof String)) throw new IllegalStateException("Corrupt owned session history");
            StatsRepository.SessionEntry session = decode((String) value.getValue());
            if (!value.getKey().equals("session:" + session.identity())) throw new IllegalStateException("Different owned session history");
            result.session(session);
        }
        return result;
    }
    static String encode(StatsRepository.SessionEntry value) {
        return String.join(",", "3", Long.toString(value.getStartMillis()), Long.toString(value.getDurationSeconds()),
                Integer.toString(value.getBlocks()), Long.toString(value.getLimitedAppMillis()), Long.toString(value.getLimitInterventions()),
                Long.toString(value.getTributeEvents()), Long.toString(value.getTributeCents()), Long.toString(value.getSubliminals()),
                Long.toString(value.getPopupImpressions()), Long.toString(value.getTamperEvents()), value.identity(), value.zone());
    }
    static StatsRepository.SessionEntry decode(String encoded) {
        List<StatsRepository.SessionEntry> rows = StatsRepository.parseSessionHistory(encoded);
        if (rows.size() != 1 || !encode(rows.get(0)).equals(encoded)) throw new IllegalStateException("Corrupt owned session history");
        return rows.get(0);
    }
    static long number(Map<String, ?> source, String key, long absent) {
        Object value = source.get(key);
        if (value == null) return absent;
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0)
            throw new IllegalStateException("Corrupt owned counter");
        return ((Number) value).longValue();
    }
    static String text(Map<String, ?> source, String key, String absent) {
        Object value = source.get(key);
        if (value == null) return absent;
        if (!(value instanceof String)) throw new IllegalStateException("Corrupt owned text");
        return (String) value;
    }
}
