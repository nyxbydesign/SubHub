package com.subhub.app.stats;

import android.content.Context;
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
        requireAccess();
        try (RemoteStore store = new RemoteStore(context)) {
            return load(store, System.currentTimeMillis());
        }
    }

    DomStatsSnapshot load(RemoteStore store, long now) {
        requireAccess();
        List<DomStatsSnapshot.Sub> values = new ArrayList<>();
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase database = store.getReadableDatabase();
            database.beginTransactionNonExclusive();
            try {
            for (RemoteStore.Peer peer : store.peers(false)) {
                if (peer.state != RemoteStore.State.ACTIVE || peer.localRole != RemoteProtocol.Role.DOM) continue;
                JSONObject summary = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.STATS, "summary");
                JSONObject live = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, "current");
                DomStatsSnapshot.Totals totals = null;
                DomStatsSnapshot.Observation runtime = null;
                if (RemoteTelemetry.validSummary(summary)) totals = new DomStatsSnapshot.Totals(
                        summary.optLong("serviceSeconds"), summary.optLong("sessions"),
                        summary.optLong("paidEUR"), summary.optLong("paidUSD"),
                        summary.optLong("assessedEUR"), summary.optLong("assessedUSD"));
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
                        confirmed(store, peer, RemoteProtocol.Stream.STATS),
                        confirmed(store, peer, RemoteProtocol.Stream.RUNTIME), totals, runtime));
            }
            requireAccess();
            database.setTransactionSuccessful();
            return DomStatsSnapshot.aggregate(values, now);
            } finally { database.endTransaction(); }
        }
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
