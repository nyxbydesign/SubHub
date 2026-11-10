package com.subhub.app.remote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Acknowledged request journal. A failed preference commit is never an approval receipt. */
final class ExtraTimeStore {
    static final Object LOCK = new Object();
    static final int MAX_ROWS = 128;
    static final String PREFS = "subhub_extra_time_v1";
    private final SharedPreferences preferences;
    private static final java.util.Map<SharedPreferences,java.util.Map<String,Cached>> cache = new java.util.WeakHashMap<>();
    private static final class Cached {
        final String raw; final List<Row> rows;
        Cached(String raw,List<Row> rows) { this.raw=raw; this.rows=List.copyOf(rows); }
    }
    private static final java.util.Set<SharedPreferences> uncertain =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    ExtraTimeStore(Context context) { preferences = context.getSharedPreferences(PREFS, 0); }
    List<Row> load(String scope) throws JSONException {
        synchronized (LOCK) {
            if (uncertain.contains(preferences)) {
                if (!preferences.edit().commit()) throw new IllegalStateException("App-time history is not saved");
                uncertain.remove(preferences);
            }
            String raw = preferences.getString(scope,"[]");
            java.util.Map<String,Cached> scopes = cache.computeIfAbsent(preferences,unused -> new java.util.HashMap<>());
            Cached cached = scopes.get(scope);
            if (cached != null && cached.raw.equals(raw)) return new ArrayList<>(cached.rows);
            JSONArray data = new JSONArray(raw);
            if (data.length() > MAX_ROWS) throw new JSONException("App-time history exceeds its bound");
            List<Row> rows = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < data.length(); i++) {
                Row row = Row.parse(data.getJSONObject(i), true);
                if (!ids.add(row.id)) throw new JSONException("Duplicate app-time request");
                rows.add(row);
            }
            scopes.put(scope,new Cached(raw,rows));
            return rows;
        }
    }
    void save(String scope, List<Row> rows) throws JSONException {
        synchronized (LOCK) {
            if (rows.size() > MAX_ROWS) throw new IllegalStateException("App-time history is full");
            JSONArray data = new JSONArray();
            for (Row row : rows) data.put(row.json(true));
            if (!preferences.edit().putString(scope, data.toString()).commit()) {
                uncertain.add(preferences);
                throw new IllegalStateException("App-time change was not saved");
            }
            uncertain.remove(preferences);
        }
    }
    static Row find(List<Row> rows, String id) {
        for (Row row : rows) if (row.id.equals(id)) return row;
        return null;
    }
    static void replace(List<Row> rows, Row value) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(value.id)) { rows.set(i, value); return; }
        rows.add(value);
    }
    static void makeRoom(List<Row> rows, long now) {
        // Only closed, old days are disposable. Never evict a current pending request to admit a new one.
        rows.removeIf(row -> now >= row.at && now - row.at > 7L * 86_400_000L);
        if (rows.size() >= MAX_ROWS) throw new IllegalStateException("App-time history is full");
    }
    static final class Row {
        final String id, packageName, label, day, zone, configuration, state, currency;
        final long at, appUsed, ceiling;
        final int dailyMinutes, minutes, tributeCents;
        Row(String id, String packageName, String label, String day, String zone, String configuration,
                long at, long appUsed, int dailyMinutes, String state, int minutes, long ceiling, int tributeCents, String currency) {
            this.id = RemoteProtocol.identifier(id);
            if (!packageName.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+") || packageName.length() > 255)
                throw new IllegalArgumentException("Invalid app package");
            if (label == null || label.trim().isEmpty() || label.length() > 160 || at <= 0 || appUsed < 0
                    || appUsed > 86_400_000L || dailyMinutes < 1 || dailyMinutes > 1440
                    || !configuration.matches("[a-f0-9]{64}") || ceiling < 0 || ceiling > 86_400_000L)
                throw new IllegalArgumentException("Invalid app-time request");
            LocalDate parsed = LocalDate.parse(day); ZoneId timeZone = ZoneId.of(zone);
            if (!Instant.ofEpochMilli(at).atZone(timeZone).toLocalDate().equals(parsed))
                throw new IllegalArgumentException("Request day mismatch");
            if (!List.of("SUBMITTING", "PENDING", "APPROVED", "DECLINED", "EXPIRED").contains(state)
                    || (state.equals("APPROVED") ? minutes < 1 || minutes > 1440 : minutes != 0))
                throw new IllegalArgumentException("Invalid app-time decision");
            if (tributeCents < 0 || tributeCents > 10_000) throw new IllegalArgumentException("Invalid tribute quote");
            this.currency = com.subhub.app.penance.WalletCurrency.requireSupported(currency); this.tributeCents = tributeCents;
            this.packageName = packageName; this.label = label; this.day = day; this.zone = zone;
            this.configuration = configuration; this.at = at; this.appUsed = appUsed;
            this.dailyMinutes = dailyMinutes; this.state = state; this.minutes = minutes; this.ceiling = ceiling;
        }
        boolean today(long now) {
            return now >= at - 30_000L && day.equals(Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)).toLocalDate().toString());
        }
        boolean immutableEquals(Row other) {
            return id.equals(other.id) && packageName.equals(other.packageName) && label.equals(other.label)
                    && day.equals(other.day) && zone.equals(other.zone) && configuration.equals(other.configuration)
                    && at == other.at && appUsed == other.appUsed && dailyMinutes == other.dailyMinutes
                    && tributeCents == other.tributeCents && currency.equals(other.currency);
        }
        Row state(String next, int approved, long nextCeiling) {
            return new Row(id, packageName, label, day, zone, configuration, at, appUsed, dailyMinutes,
                    next, approved, nextCeiling, tributeCents, currency);
        }
        Row unsubmittedExpiry(int assessedCents) {
            if (!state.equals("SUBMITTING")) throw new IllegalStateException("Submission is already public");
            return new Row(id,packageName,label,day,zone,configuration,at,appUsed,dailyMinutes,
                    "EXPIRED",0,0,assessedCents,currency);
        }
        JSONObject json(boolean local) throws JSONException {
            JSONObject value = new JSONObject().put("id", id).put("package", packageName).put("label", label)
                    .put("day", day).put("zone", zone).put("configuration", configuration).put("at", at)
                    .put("used", appUsed).put("daily", dailyMinutes).put("state", state).put("minutes", minutes).put("tribute", tributeCents).put("currency", currency);
            if (local) value.put("ceiling", ceiling);
            return value;
        }
        static Row parse(JSONObject value, boolean local) throws JSONException {
            RemoteSessionCommands.keys(value, local ? new String[]{"id","package","label","day","zone","configuration","at","used","daily","state","minutes","tribute","currency","ceiling"}
                    : new String[]{"id","package","label","day","zone","configuration","at","used","daily","state","minutes","tribute","currency"});
            try {
                return new Row(value.getString("id"), value.getString("package"), value.getString("label"),
                        value.getString("day"), value.getString("zone"), value.getString("configuration"),
                        RemoteProtocol.integer(value,"at"), RemoteProtocol.integer(value,"used"),
                        exactInt(value,"daily"), value.getString("state"), exactInt(value,"minutes"),
                        local ? RemoteProtocol.integer(value,"ceiling") : 0, exactInt(value,"tribute"), value.getString("currency"));
            } catch (IllegalArgumentException invalid) { throw new JSONException("Invalid app-time request fields"); }
        }
        private static int exactInt(JSONObject value, String name) throws JSONException {
            long number = RemoteProtocol.integer(value, name);
            if (number < 0 || number > Integer.MAX_VALUE) throw new JSONException("Invalid app-time integer");
            return (int) number;
        }
    }
}
