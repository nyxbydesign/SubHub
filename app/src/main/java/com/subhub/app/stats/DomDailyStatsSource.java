package com.subhub.app.stats;

import android.content.Context;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.remote.RemoteTelemetry;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Reads existing paired daily mirrors without starting transport or collecting new history. */
final class DomDailyStatsSource implements DailyStatsPanel.Source {
    private final Context context;

    DomDailyStatsSource(Context context) { this.context = context.getApplicationContext(); }

    @Override public boolean accessible() { return AchievementRole.DOM.accessible(context); }
    @Override public boolean combined() { return true; }

    @Override public DailyStatsPanel.DailyData load(String first, String last) throws Exception {
        requireAccess();
        try (RemoteStore store = new RemoteStore(context)) { return load(store, first, last); }
    }

    DailyStatsPanel.DailyData load(RemoteStore store, String first, String last) throws Exception {
        requireAccess();
        List<DomDailySnapshot.History> histories = new ArrayList<>();
        synchronized (store) {
            android.database.sqlite.SQLiteDatabase database = store.getReadableDatabase();
            database.beginTransactionNonExclusive();
            try {
                for (RemoteStore.Peer peer : store.peers(false)) {
                    if (peer.state != RemoteStore.State.ACTIVE || peer.localRole != RemoteProtocol.Role.DOM) continue;
                    Map<String, Map<String, Long>> days = new LinkedHashMap<>();
                    for (RemoteProtocol.Change row : store.mirror(peer.identity, peer.generation, RemoteProtocol.Stream.DAILY)) {
                        if (row.deleted) continue;
                        JSONObject entry = new JSONObject(row.body);
                        if (RemoteProtocol.integer(entry, "schema") != 1) throw new IOException("Unsupported history");
                        String day = LocalDate.parse(entry.getString("day")).toString();
                        if (!day.equals(row.key)) throw new IOException("Mismatched history date");
                        JSONObject raw = entry.getJSONObject("metrics");
                        Map<String, Long> metrics = new LinkedHashMap<>();
                        for (Iterator<String> keys = raw.keys(); keys.hasNext();) {
                            String key = keys.next();
                            long amount = RemoteProtocol.integer(raw, key);
                            if (amount < 0) throw new IOException("Invalid history");
                            metrics.put(key, amount);
                        }
                        days.put(day, metrics);
                    }
                    JSONObject runtime = store.mirrored(peer.identity, peer.generation, RemoteProtocol.Stream.RUNTIME, "current");
                    JSONObject consent = RemoteTelemetry.validRuntime(runtime) ? runtime.optJSONObject("featureConsent") : null;
                    boolean wallet = consent != null && Boolean.TRUE.equals(consent.opt("wallet"));
                    DomStatsSnapshot.Sub identity = new DomStatsSnapshot.Sub(peer.identity, peer.generation,
                            store.additionOrder(peer.identity, peer.generation), 0, 0, null, null);
                    histories.add(new DomDailySnapshot.History(identity, wallet, days));
                }
                DomDailySnapshot snapshot = DomDailySnapshot.aggregate(histories, first, last);
                requireAccess();
                database.setTransactionSuccessful();
                return new DailyStatsPanel.DailyData(snapshot.since, snapshot.days);
            } finally { database.endTransaction(); }
        }
    }

    private void requireAccess() {
        if (!accessible()) throw new SecurityException("Dom statistics unavailable");
    }
}
