package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.relationships.DomSubjectScope;
import com.subhub.app.relationships.RelationshipUi;
import com.subhub.app.remote.RemotePersonRepository;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.remote.RemoteTelemetry;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Reads retained, relationship-scoped mirrors without requesting sync or starting services. */
public final class DomStatsRepository {
    private final Context context;

    public DomStatsRepository(Context context) { this.context = context.getApplicationContext(); }

    public DomStatsSnapshot load() {
        return loadWithHistory().stats;
    }

    Loaded loadWithHistory() {
        requireAccess();
        DomSubjectScope scope = scope();
        LocalRetainedStatistics local = null;
        if (scope.includes(com.subhub.app.people.PersonId.LOCAL)) {
            try { local = LocalRetainedStatistics.load(context); }
            catch (Exception unavailable) { throw new IllegalStateException("Local statistics unavailable", unavailable); }
        }
        try (RemoteStore store = new RemoteStore(context)) {
            Loaded result = read(store, System.currentTimeMillis(), local, scope);
            revalidate(scope);
            return result;
        }
    }

    DomStatsSnapshot load(RemoteStore store, long now) {
        return load(store, now, null);
    }

    DomStatsSnapshot load(RemoteStore store, long now, LocalRetainedStatistics local) {
        outsideStore(store);
        DomSubjectScope scope = scope();
        Loaded result = read(store, now, local, scope);
        revalidate(scope);
        return result.stats;
    }

    private Loaded read(RemoteStore store, long now, LocalRetainedStatistics local, DomSubjectScope scope) {
        requireAccess();
        scope.requireCurrent();
        if (!scope.includes(com.subhub.app.people.PersonId.LOCAL)) local = null;
        List<DomStatsSnapshot.Sub> values = new ArrayList<>();
        if (local != null) values.add(localIdentity(context, local, scope, now));
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase database = store.getReadableDatabase();
            database.beginTransactionNonExclusive();
            try {
            java.util.Map<String, RetainedStatistics> retained = new java.util.HashMap<>();
            List<DomDailySnapshot.History> histories;
            try {
                histories = new DomDailyStatsSource(context).histories(store, local, scope);
                for (DomDailySnapshot.History history : histories)
                    retained.put(history.identity.identity + ":" + history.identity.generation,
                            new RetainedStatistics(java.time.ZoneId.systemDefault(), now, history.days));
            } catch (Exception unavailable) { throw new IllegalStateException("Retained peer statistics unavailable", unavailable); }
            for (RemoteStore.Peer peer : store.peers(false)) {
                if (peer.state != RemoteStore.State.ACTIVE || peer.localRole != RemoteProtocol.Role.DOM
                        || !scope.includes(RemotePersonRepository.id(peer))) continue;
                JSONObject summary = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.STATS, "summary");
                JSONObject live = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, "current");
                DomStatsSnapshot.Totals totals = null;
                DomStatsSnapshot.Observation runtime = null;
                RetainedStatistics history = retained.get(peer.identity + ":" + peer.generation);
                boolean summarized = RemoteTelemetry.validSummary(summary);
                boolean dated = history != null && (history.sessionSeconds() > 0 || history.total("sessions") > 0);
                if (summarized || dated) {
                    long seconds = history == null ? 0 : history.sessionSeconds(), sessions = history == null ? 0 : history.total("sessions");
                    long paidEur = history == null ? 0 : history.total("paid_EUR"), paidUsd = history == null ? 0 : history.total("paid_USD");
                    long assessedEur = history == null ? 0 : history.total("assessed_EUR"), assessedUsd = history == null ? 0 : history.total("assessed_USD");
                    totals = new DomStatsSnapshot.Totals(
                            Math.max(summarized ? summary.optLong("serviceSeconds") : 0, seconds),
                            Math.max(summarized ? summary.optLong("sessions") : 0, sessions),
                            Math.max(summarized ? summary.optLong("paidEUR") : 0, paidEur),
                            Math.max(summarized ? summary.optLong("paidUSD") : 0, paidUsd),
                            Math.max(summarized ? summary.optLong("assessedEUR") : 0, assessedEur),
                            Math.max(summarized ? summary.optLong("assessedUSD") : 0, assessedUsd));
                }
                if (RemoteTelemetry.validRuntime(live)) {
                    JSONObject consent = live.optJSONObject("featureConsent");
                    Boolean wallet = consent != null && consent.opt("wallet") instanceof Boolean
                            ? consent.optBoolean("wallet") : null;
                    Boolean hardcore = live.opt("hardcoreEnabled") instanceof Boolean
                            ? live.optBoolean("hardcoreEnabled") : null;
                    runtime = new DomStatsSnapshot.Observation(live.optBoolean("serviceActive"), hardcore, wallet);
                }
                values.add(new DomStatsSnapshot.Sub(peer.identity, peer.generation,
                        store.additionOrder(peer.identity, peer.generation),
                        summarized ? confirmed(store, peer, RemoteProtocol.Stream.STATS) : 0,
                        confirmed(store, peer, RemoteProtocol.Stream.RUNTIME), totals, runtime));
            }
            requireAccess();
            database.setTransactionSuccessful();
            scope.requireCurrent();
            return new Loaded(DomStatsSnapshot.aggregate(values, now), local, histories, scope);
            } finally { database.endTransaction(); }
        }
    }

    static final class Loaded {
        final DomSubjectScope scope;
        final DomStatsSnapshot stats;
        final LocalRetainedStatistics local;
        final List<DomDailySnapshot.History> histories;
        Loaded(DomStatsSnapshot stats, LocalRetainedStatistics local, List<DomDailySnapshot.History> histories, DomSubjectScope scope) {
            this.stats = stats; this.local = local; this.histories = histories; this.scope = scope;
        }
    }

    private DomSubjectScope scope() {
        try { return DomSubjectScope.read(context); }
        catch (Exception unavailable) { throw new IllegalStateException("Dom subjects unavailable", unavailable); }
    }

    static void revalidate(DomSubjectScope scope) {
        try { scope.revalidate(); }
        catch (Exception unavailable) { throw new IllegalStateException("Dom subjects changed", unavailable); }
    }

    static void outsideStore(RemoteStore store) {
        if (Thread.holdsLock(store) || store.getReadableDatabase().inTransaction())
            throw new IllegalStateException("Dom subjects require no outer store transaction");
    }

    static DomStatsSnapshot.Sub localIdentity(Context context, LocalRetainedStatistics local,
            DomSubjectScope scope, long now) {
        RelationshipUi.Edge edge = scope.localEdge();
        if (edge == null) throw new IllegalStateException("Local Sub is not linked");
        DomStatsSnapshot.Sub value = local.aggregate(context, now);
        return new DomStatsSnapshot.Sub(value.identity, edge.generation, edge.insertionOrder,
                value.statsConfirmedAt, value.runtimeConfirmedAt, value.totals, value.runtime);
    }

    private static long confirmed(RemoteStore store, RemoteStore.Peer peer, RemoteProtocol.Stream stream) {
        try { return Math.max(0, Long.parseLong(store.metadata(peer.identity, peer.generation,
                "confirmed_" + stream.name(), "0"))); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private void requireAccess() {
        if (!AchievementRole.DOM.accessible(context)) throw new SecurityException("Dom statistics unavailable");
    }
}
