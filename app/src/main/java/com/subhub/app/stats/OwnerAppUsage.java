package com.subhub.app.stats;

import android.content.SharedPreferences;
import android.os.Looper;
import com.subhub.app.appmode.AppTimerManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Actual isolated usage, cumulative history and session counters in one owner transaction. */
public final class OwnerAppUsage implements AppTimerManager.OwnerUsage {
    public interface Store {
        SharedPreferences usage();
        SharedPreferences history();
        void requireSessionWrite();
        /** Same guarded atomic backend as the supplied statistics counters. */
        <T> T transaction(Supplier<T> action);
    }
    private static final String DAY = "day", ZONE = "zone", EPOCH = "epoch", TOTAL = "total_ms", APP = "app:";
    // TOTAL/APP enforce the whole civil day's allowance. Epoch counters retain exact zone facts.
    private static final String EPOCH_TOTAL = "epoch_total_ms", EPOCH_APP = "epoch_app:";
    private final Store store;
    private final StatsRepository stats;
    public OwnerAppUsage(Store store, StatsRepository stats) {
        this.store = Objects.requireNonNull(store); this.stats = Objects.requireNonNull(stats);
    }
    @Override public AppTimerManager.UsageSnapshot snapshot(String packageName, long now) {
        return snapshot(packageName, OwnerObservation.capture(now));
    }
    public AppTimerManager.UsageSnapshot snapshot(String packageName, OwnerObservation observation) {
        return snapshots(Collections.singleton(packageName == null ? "" : packageName), observation)
                .get(packageName == null ? "" : packageName);
    }
    @Override public Map<String, AppTimerManager.UsageSnapshot> snapshots(Set<String> packages, long now) {
        return snapshots(packages, OwnerObservation.capture(now));
    }
    public Map<String, AppTimerManager.UsageSnapshot> snapshots(Set<String> packages, OwnerObservation observation) {
        Objects.requireNonNull(packages); Objects.requireNonNull(observation);
        Map<String, ?> observed = store.usage().getAll();
        validate(observed);
        boolean today = observation.day().equals(observed.get(DAY));
        long total = today ? number(observed, TOTAL) : 0;
        Map<String, AppTimerManager.UsageSnapshot> result = new LinkedHashMap<>();
        result.put("", AppTimerManager.UsageSnapshot.measured(total, total));
        for (String name : packages) {
            requirePackage(name);
            long used = name.isEmpty() ? total : today ? number(observed, APP + name) : 0;
            result.put(name, AppTimerManager.UsageSnapshot.measured(used, total));
        }
        return Collections.unmodifiableMap(result);
    }
    @Override public void recordUsage(String packageName, long elapsedMillis, long now) {
        recordUsage(packageName, elapsedMillis, OwnerObservation.capture(now));
    }
    public void recordUsage(String packageName, long elapsedMillis, OwnerObservation observation) {
        Objects.requireNonNull(observation);
        long now = observation.at;
        requireWorker(); requirePackage(packageName); requireClock(now);
        if (packageName.isEmpty() || elapsedMillis <= 0) throw new IllegalArgumentException("Invalid owned usage delta");
        store.transaction(() -> {
            store.requireSessionWrite();
            StatsRepository.SessionStamp session = stats.observedSessionStamp();
            if (session == null || session.isDeviceSession() || !stats.ownsSessionStamp(session) || !session.isCurrent())
                throw new SecurityException("No admitted owner statistics session");
            SharedPreferences usage = store.usage();
            Map<String, ?> before = usage.getAll(); validate(before);
            String today = observation.day();
            SharedPreferences.Editor edit = usage.edit();
            boolean sameDay = today.equals(before.get(DAY));
            boolean sameEpoch = sameDay && observation.zone.equals(before.get(ZONE));
            if (!sameEpoch) {
                archive(before);
                edit.clear().putString(DAY, today).putString(ZONE, observation.zone)
                        .putString(EPOCH, UUID.randomUUID().toString());
                // Travel within one civil date never grants another full allowance.
                if (sameDay) for (Map.Entry<String, ?> value : before.entrySet())
                    if (value.getKey().startsWith(APP)) edit.putLong(value.getKey(), (Long) value.getValue());
            }
            long priorTotal = sameDay ? number(before, TOTAL) : 0;
            long priorApp = sameDay ? number(before, APP + packageName) : 0;
            edit.putLong(TOTAL, Math.addExact(priorTotal, elapsedMillis))
                    .putLong(APP + packageName, Math.addExact(priorApp, elapsedMillis));
            // Old acknowledged single-epoch sources had no separate budget/provenance counters.
            // Preserve their existing epoch and known counts when first upgrading in place.
            boolean separated = before.containsKey(EPOCH_TOTAL);
            if (sameEpoch && !separated) for (Map.Entry<String, ?> value : before.entrySet())
                if (value.getKey().startsWith(APP))
                    edit.putLong(EPOCH_APP + value.getKey().substring(APP.length()), (Long) value.getValue());
            long priorEpochTotal = sameEpoch ? number(before, separated ? EPOCH_TOTAL : TOTAL) : 0;
            long priorEpochApp = sameEpoch ? number(before, (separated ? EPOCH_APP : APP) + packageName) : 0;
            edit.putLong(EPOCH_TOTAL, Math.addExact(priorEpochTotal, elapsedMillis))
                    .putLong(EPOCH_APP + packageName, Math.addExact(priorEpochApp, elapsedMillis));
            if (!edit.commit()) throw new IllegalStateException("Owned usage source not acknowledged");
            stats.recordLimitedAppUsage(elapsedMillis, observation);
            archive(usage.getAll()); // Same atomic transaction; no unowned delayed timer.
            return null;
        });
    }
    @Override public void checkpointHistory(long now) {
        requireWorker(); requireClock(now);
        store.transaction(() -> { Map<String, ?> values = store.usage().getAll(); validate(values); archive(values); return null; });
    }
    public List<DailyAppUsageStore.Entry> entries() {
        List<DailyAppUsageStore.Entry> result = new ArrayList<>();
        for (Map.Entry<String, ?> value : store.history().getAll().entrySet()) {
            if (!value.getKey().startsWith("row:") || !(value.getValue() instanceof String))
                throw new IllegalStateException("Corrupt owned usage history");
            String[] parts = ((String) value.getValue()).split(";", -1);
            try {
                if (parts.length != 5) throw new IllegalArgumentException();
                DailyAppUsageStore.Entry row = new DailyAppUsageStore.Entry(parts[0], parts[1], parts[2], parts[3], Long.parseLong(parts[4]));
                if (!value.getKey().equals(key(row.epoch, row.packageName))) throw new IllegalArgumentException();
                result.add(row);
            } catch (RuntimeException invalid) { throw new IllegalStateException("Corrupt owned usage history", invalid); }
        }
        return Collections.unmodifiableList(result);
    }
    private void archive(Map<String, ?> values) {
        if (values.isEmpty()) return;
        validate(values);
        String epoch = (String) values.get(EPOCH), date = (String) values.get(DAY), zone = (String) values.get(ZONE);
        SharedPreferences history = store.history(); SharedPreferences.Editor edit = history.edit();
        String totalKey = values.containsKey(EPOCH_TOTAL) ? EPOCH_TOTAL : TOTAL;
        String appPrefix = values.containsKey(EPOCH_TOTAL) ? EPOCH_APP : APP;
        for (Map.Entry<String, ?> value : values.entrySet()) {
            if (!(value.getKey().equals(totalKey) || value.getKey().startsWith(appPrefix))) continue;
            String name = value.getKey().equals(totalKey) ? "" : value.getKey().substring(appPrefix.length());
            long millis = (Long) value.getValue(); String rowKey = key(epoch, name);
            String row = String.join(";", epoch, date, zone, name, Long.toString(millis));
            String previous = history.getString(rowKey, null);
            if (previous != null) {
                String[] parts = previous.split(";", -1);
                if (parts.length != 5 || !parts[0].equals(epoch) || !parts[1].equals(date)
                        || !parts[2].equals(zone) || !parts[3].equals(name) || Long.parseLong(parts[4]) > millis)
                    throw new IllegalStateException("Owned usage history regressed");
            }
            edit.putString(rowKey, row);
        }
        if (!edit.commit()) throw new IllegalStateException("Owned usage history not acknowledged");
    }
    private static String key(String epoch, String name) { return "row:" + epoch + ":" + name; }
    private static void requireClock(long now) { if (now <= 0) throw new IllegalArgumentException("Invalid usage clock"); }
    private static void requireWorker() {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Owned usage writes must be off main");
    }
    private static void requirePackage(String name) {
        if (name == null || !name.isEmpty() && (name.length() > 255 || !name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")))
            throw new IllegalArgumentException("Invalid owned usage package");
    }
    private static long number(Map<String, ?> values, String key) {
        Object value = values.get(key); if (value == null) return 0;
        if (!(value instanceof Long) || (Long) value < 0) throw new IllegalStateException("Corrupt owned usage counter");
        return (Long) value;
    }
    private static void validate(Map<String, ?> values) {
        if (values.isEmpty()) return; // Only an acknowledged, explicitly provisioned backend may return empty.
        try {
            String epoch = (String) values.get(EPOCH);
            if (!UUID.fromString(epoch).toString().equals(epoch) || !values.containsKey(TOTAL))
                throw new IllegalStateException("Incomplete owned usage source");
            LocalDate.parse((String) values.get(DAY)); ZoneId.of((String) values.get(ZONE));
            long total = number(values, TOTAL), daySum = 0, epochSum = 0;
            boolean separated = values.containsKey(EPOCH_TOTAL);
            long epochTotal = separated ? number(values, EPOCH_TOTAL) : total;
            if (epochTotal > total) throw new IllegalStateException("Owned epoch exceeds day budget");
            for (Map.Entry<String, ?> value : values.entrySet()) {
                String key = value.getKey();
                if (key.equals(EPOCH) || key.equals(DAY) || key.equals(ZONE) || key.equals(TOTAL) || key.equals(EPOCH_TOTAL)) continue;
                boolean epochCounter = key.startsWith(EPOCH_APP);
                String prefix = epochCounter ? EPOCH_APP : APP;
                if (!key.startsWith(prefix) || epochCounter && !separated)
                    throw new IllegalStateException("Unknown owned usage field");
                String name = key.substring(prefix.length());
                if (name.isEmpty()) throw new IllegalStateException("Empty owned usage package");
                requirePackage(name); long count = number(values, key);
                if (epochCounter) {
                    if (count > epochTotal || count > number(values, APP + name))
                        throw new IllegalStateException("Owned epoch exceeds package budget");
                    epochSum = Math.addExact(epochSum, count);
                } else {
                    if (count > total) throw new IllegalStateException("Owned usage exceeds aggregate");
                    daySum = Math.addExact(daySum, count);
                }
            }
            if (daySum != total || separated && epochSum != epochTotal)
                throw new IllegalStateException("Owned usage aggregate does not match its packages");
        } catch (RuntimeException invalid) { throw new IllegalStateException("Corrupt owned usage source", invalid); }
    }
}
