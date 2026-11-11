package com.subhub.app.people;

import com.subhub.app.appmode.AppTimerManager;
import com.subhub.app.internal.InternalRuntimeRecord;
import com.subhub.app.internal.InternalStorageScope;
import com.subhub.app.internal.InternalSubRecord;
import com.subhub.app.penance.PenanceEvent;
import com.subhub.app.penance.WalletCurrency;
import com.subhub.app.stats.OwnerObservation;
import com.subhub.app.stats.OwnerStatisticsStore;
import com.subhub.app.stats.StatsSnapshot;
import java.util.*;

/** One immutable cross-namespace observation. It is data, never runtime or mutation authority. */
public final class InternalRuntimePresentationSnapshot {
    public final InternalSubRecord record;
    public final InternalRuntimeRecord runtime;
    public final OwnerStatisticsStore.Snapshot accounting;
    public final OwnerObservation observed;
    public final Map<String, AppTimerManager.UsageSnapshot> usage;
    public final List<PenanceEvent> assessedEvents;
    public InternalRuntimePresentationSnapshot(InternalSubRecord record, InternalRuntimeRecord runtime,
            OwnerStatisticsStore.Snapshot accounting, OwnerObservation observed,
            Map<String, AppTimerManager.UsageSnapshot> usage, List<PenanceEvent> assessedEvents) {
        this.record = Objects.requireNonNull(record); this.runtime = Objects.requireNonNull(runtime);
        this.accounting = Objects.requireNonNull(accounting); this.observed = Objects.requireNonNull(observed);
        if (!InternalStorageScope.TARGET_ID.equals(record.targetId) || !record.visible || accounting.revision != runtime.revision)
            throw new IllegalArgumentException("Incoherent internal presentation observation");
        Map<String, AppTimerManager.UsageSnapshot> copied = new LinkedHashMap<>();
        for (Map.Entry<String, AppTimerManager.UsageSnapshot> entry : Objects.requireNonNull(usage).entrySet()) {
            String key = Objects.requireNonNull(entry.getKey());
            if (!key.isEmpty() && !key.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))
                throw new IllegalArgumentException("Invalid internal usage identity");
            AppTimerManager.UsageSnapshot value = Objects.requireNonNull(entry.getValue());
            copied.put(key, AppTimerManager.UsageSnapshot.measured(value.appUsedMillis, value.totalUsedMillis));
        }
        if (!copied.containsKey("")) throw new IllegalArgumentException("Missing internal aggregate usage observation");
        this.usage = Collections.unmodifiableMap(copied);
        List<PenanceEvent> events = new ArrayList<>();
        for (PenanceEvent event : Objects.requireNonNull(assessedEvents)) events.add(Objects.requireNonNull(event));
        this.assessedEvents = Collections.unmodifiableList(events);
    }
    public long revision() { return runtime.revision; }
    public StatsSnapshot statistics() { return accounting.statistics; }
    public AppTimerManager.UsageSnapshot usage(String packageName) {
        AppTimerManager.UsageSnapshot value = usage.get(packageName);
        if (value == null) throw new IllegalStateException("Package was not observed in this internal snapshot");
        return value;
    }
    public long assessed(String currency) {
        WalletCurrency.requireSupported(currency);
        long ledger = 0, dated = 0;
        for (PenanceEvent event : assessedEvents) if (currency.equals(event.getCurrency())) ledger = Math.addExact(ledger, event.getAmountCents());
        for (Map<String, Long> day : accounting.days().values()) dated = Math.addExact(dated, day.getOrDefault("assessed_" + currency, 0L));
        String key = "tribute_cents" + ("EUR".equals(currency) ? "" : "_" + currency);
        Object value = runtime.namespaces().getOrDefault("statistics", Collections.emptyMap()).get(key);
        if (value != null && (!(value instanceof Long) || (Long) value < 0)) throw new IllegalStateException("Invalid internal assessed total");
        return Math.max(Math.max(ledger, dated), value == null ? 0 : (Long) value);
    }
    /** True only for the same profile/configuration and acknowledged data revision. No IO. */
    public boolean matches(InternalSubRecord current, long acknowledgedRevisionHint) {
        return current != null && current.visible && record.generation.equals(current.generation)
                && record.revision == current.revision && runtime.revision == acknowledgedRevisionHint;
    }
}
