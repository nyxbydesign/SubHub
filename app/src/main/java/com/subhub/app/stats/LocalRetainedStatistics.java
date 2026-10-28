package com.subhub.app.stats;

import android.content.Context;
import android.os.Looper;
import com.subhub.app.capture.export.ExportJobStore;
import com.subhub.app.penance.PenanceEvent;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** Existing on-device evidence, without media access, transport startup or effect replay. */
final class LocalRetainedStatistics {
    final StatsRepository repository;
    final StatsSnapshot stats;
    final RetainedStatistics history;
    final Map<String, Long> paid, assessed;
    final Set<String> activityCopies;
    final String collectionStarted, since;

    private LocalRetainedStatistics(StatsRepository repository, StatsSnapshot stats, RetainedStatistics history,
            Map<String, Long> paid, Map<String, Long> assessed, Set<String> ids,
            String collectionStarted, String since) {
        this.repository = repository; this.stats = stats; this.history = history;
        this.paid = Collections.unmodifiableMap(paid); this.assessed = Collections.unmodifiableMap(assessed);
        activityCopies = Collections.unmodifiableSet(ids); this.collectionStarted = collectionStarted; this.since = since;
    }

    static LocalRetainedStatistics load(Context context) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("History projection must be off main");
        if (!DeviceRolePreferences.supportsSub(context)) throw new SecurityException("Local Sub unavailable");
        long now = System.currentTimeMillis();
        StatsRepository repository = new StatsRepository(context, false);
        StatsSnapshot original = repository.retainedSnapshot();
        DailyStatsStore.awaitWrites();
        DailyStatsStore daily = DailyStatsStore.get(context);
        RetainedStatistics history = new RetainedStatistics(ZoneId.systemDefault(), now,
                daily.days("0000-01-01", "9999-12-31"));
        history.activeDates(original.getActiveDates());
        for (StatsRepository.SessionEntry session : repository.getSessionHistory()) history.session(session);
        if (context.getDatabasePath("daily_app_usage.db").isFile()) {
            try (DailyAppUsageStore usage = new DailyAppUsageStore(context, "daily_app_usage.db")) {
                Map<String, Map<String, DailyAppUsageStore.Entry>> epochs = new LinkedHashMap<>();
                for (DailyAppUsageStore.Entry entry : usage.entries())
                    epochs.computeIfAbsent(entry.epoch, ignored -> new LinkedHashMap<>()).put(entry.packageName, entry);
                long retainedUsage = 0;
                for (Map.Entry<String, Map<String, DailyAppUsageStore.Entry>> epoch : epochs.entrySet()) {
                    DailyAppUsageStore.Entry total = epoch.getValue().get("");
                    if (total != null) {
                        history.event("limits", epoch.getKey(), total.day, "limits_ms", total.millis);
                        retainedUsage = DomStatsSnapshot.add(retainedUsage, total.millis);
                    }
                    else {
                        long amount = 0; String day = null;
                        for (DailyAppUsageStore.Entry entry : epoch.getValue().values()) {
                            amount = Math.max(amount, entry.millis); day = entry.day;
                        }
                        if (day != null) history.event("limits", epoch.getKey(), day, "limits_ms", amount);
                        retainedUsage = DomStatsSnapshot.add(retainedUsage, amount);
                    }
                }
                history.undated("limits_ms", retainedUsage);
            }
        }
        Set<String> activityIds = new LinkedHashSet<>();
        if (context.getDatabasePath("remote_sync_v1.db").isFile()) {
            try (RemoteStore store = new RemoteStore(context)) {
                store.readRetainedLocalStatistics(value -> {
                    if (!"APP_OPENED".equals(value.optString("kind"))) activityIds.add(activityIdentity(value));
                    activity(history, value, now);
                });
            } catch (org.json.JSONException invalid) {
                // Retain other evidence when an old Activity representation cannot be decoded.
            }
        }
        PenanceManager.RetainedStatistics ledger = PenanceManager.readRetainedStatistics(context);
        Map<String, Long> ledgerAssessed = currencies();
        long retainedTributes = 0;
        for (PenanceEvent event : ledger.events) {
            String currency = event.getCurrency();
            if (!ledgerAssessed.containsKey(currency) || event.getAmountCents() < 0) continue;
            ledgerAssessed.put(currency, DomStatsSnapshot.add(ledgerAssessed.get(currency), event.getAmountCents()));
            retainedTributes++;
            if (event.getCreatedAtMillis() <= 0 || event.getCreatedAtMillis() > now) continue;
            String day = history.day(event.getCreatedAtMillis()), id = "tribute_" + event.getId();
            history.event("assessed", id, day, "assessed_" + currency, event.getAmountCents());
            history.event("assessed", id, day, "tributes", 1);
            // PAID has no completion clock in the retained ledger. Never use creation as payment time.
        }
        history.undated("tributes", retainedTributes);
        for (String currency : ledgerAssessed.keySet()) history.undated("assessed_" + currency, ledgerAssessed.get(currency));
        if (context.getDatabasePath("export_jobs.db").isFile()) {
            try (ExportJobStore exports = new ExportJobStore(context)) {
                long photos = 0;
                for (ExportJobStore.SavedStatistic saved : exports.retainedStatistics()) {
                    if ("photos".equals(saved.kind)) photos++;
                    if (saved.savedAt <= 0 || saved.savedAt > now || !("photos".equals(saved.kind) || "videos".equals(saved.kind))) continue;
                    try {
                        String day = Instant.ofEpochMilli(saved.savedAt).atZone(ZoneId.of(saved.zone)).toLocalDate().toString();
                        history.event("exports", saved.id, day, saved.kind, 1);
                    } catch (RuntimeException invalidClock) { /* Missing civil clock remains undated. */ }
                }
                history.undated("photos", photos);
            }
        }
        Map<String, Long> paid = currencies(), assessed = currencies();
        for (String currency : paid.keySet()) {
            paid.put(currency, Math.max(ledger.paidCents.get(currency), history.total("paid_" + currency)));
            assessed.put(currency, Math.max(repository.tributeCents(currency, false),
                    Math.max(ledgerAssessed.get(currency), history.total("assessed_" + currency))));
        }
        StatsSnapshot recovered = original.withRetained(history);
        String since = daily.since();
        for (String day : history.days().keySet()) if (day.compareTo(since) < 0) since = day;
        if (!DeviceRolePreferences.supportsSub(context)) throw new SecurityException("Local Sub unavailable");
        return new LocalRetainedStatistics(repository, recovered, history, paid, assessed,
                activityIds, daily.collectionStartedDay(), since);
    }

    DomStatsSnapshot.Sub aggregate(Context context, long now) {
        return new DomStatsSnapshot.Sub("local", "on-device", Long.MAX_VALUE, now, now,
                new DomStatsSnapshot.Totals(stats.getTotalSessionSeconds(), stats.getSessions(),
                        paid.get("EUR"), paid.get("USD"), assessed.get("EUR"), assessed.get("USD")),
                new DomStatsSnapshot.Observation(observedActive(context),
                        new HardcoreModeManager(context).isEnabled(), SubFeaturePolicy.allowed(context, "wallet")));
    }

    private static boolean observedActive(Context context) {
        return com.subhub.app.commitment.CommitmentManager.observedActive(context)
                || new com.subhub.app.penance.PaidPauseManager(context).observedActive()
                || context.getSharedPreferences(com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0)
                        .getBoolean(com.subhub.app.appmode.AppModeManager.KEY_ARMED, false)
                || com.subhub.app.service.ScreenCaptureService.isRunning()
                || com.subhub.app.service.ScreenshotAccessibilityService.isRecognitionActive();
    }

    Map<String, Map<String, Long>> range(String first, String last) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        Map<String, Map<String, Long>> retained = history.days();
        for (LocalDate date = LocalDate.parse(first); !date.isAfter(LocalDate.parse(last)); date = date.plusDays(1)) {
            String day = date.toString();
            Map<String, Long> values = retained.get(day);
            if (values != null) {
                Map<String, Long> complete = new LinkedHashMap<>(values);
                if (collectionStarted == null || day.compareTo(collectionStarted) < 0)
                    for (String metric : RetainedStatistics.METRICS) complete.putIfAbsent(metric, -1L);
                result.put(day, Collections.unmodifiableMap(complete));
            }
            else if (collectionStarted != null && day.compareTo(collectionStarted) >= 0)
                result.put(day, Collections.emptyMap());
        }
        return Collections.unmodifiableMap(result);
    }

    static void activity(RetainedStatistics history, JSONObject value, long now) {
        String id = value.optString("id"), day = value.optString("day");
        long time = value.optLong("observedAt");
        if (time <= 0 || time > now) return;
        try {
            if (com.subhub.app.remote.RemoteProtocol.integer(value, "schema") != 1
                    || !(value.get("id") instanceof String)
                    || com.subhub.app.remote.RemoteProtocol.integer(value, "observedAt") != time
                    || !Instant.ofEpochMilli(time).atZone(ZoneId.of(value.getString("zone"))).toLocalDate().toString().equals(day)) return;
            com.subhub.app.remote.RemoteProtocol.identifier(id);
        } catch (org.json.JSONException | IllegalArgumentException invalid) { return; }
        String kind = value.optString("kind");
        if ("TRIBUTE_TRIGGERED".equals(kind)) {
            String currency = value.optString("currency");
            if (!("EUR".equals(currency) || "USD".equals(currency))) return;
            history.event("assessed", id, day, "assessed_" + currency, value.optLong("amountCents"));
            history.event("assessed", id, day, "tributes", 1);
        } else if ("APP_LIMIT_REACHED".equals(kind) || "SHARED_LIMIT_REACHED".equals(kind))
            history.event("activity", id, day, "limit_stops", 1);
    }

    static String activityIdentity(JSONObject value) {
        return com.subhub.app.remote.RemoteProtocol.sha256(com.subhub.app.remote.RemoteProtocol.canonical(value)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Map<String, Long> currencies() {
        Map<String, Long> result = new LinkedHashMap<>(); result.put("EUR", 0L); result.put("USD", 0L); return result;
    }
}
