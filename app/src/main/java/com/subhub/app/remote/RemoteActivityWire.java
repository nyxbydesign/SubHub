package com.subhub.app.remote;

import com.subhub.app.activity.ActivityEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import org.json.JSONException;
import org.json.JSONObject;

/** Only source-observed facts cross the activity stream, with an explicit civil clock. */
final class RemoteActivityWire {
    private RemoteActivityWire() {}

    static JSONObject encode(ActivityEvent event, String zone, String configuration) throws JSONException {
        JSONObject value = new JSONObject().put("schema", 1).put("id", event.id)
                .put("kind", event.kind.name()).put("observedAt", event.observedAt)
                .put("packageName", event.packageName).put("amountCents", event.amountCents)
                .put("currency", event.currency).put("reason", event.reason)
                .put("limitMinutes", event.limitMinutes).put("zone", zone)
                .put("day", Instant.ofEpochMilli(event.observedAt).atZone(ZoneId.of(zone)).toLocalDate().toString())
                .put("configuration", configuration);
        decode(value);
        return value;
    }

    static ActivityEvent decode(JSONObject value) throws JSONException {
        if (value == null || value.length() != 12 || RemoteProtocol.integer(value, "schema") != 1
                || value.toString().getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new JSONException("Unsupported activity record");
        String id = RemoteProtocol.identifier(string(value, "id"));
        ActivityEvent.Kind kind;
        try { kind = ActivityEvent.Kind.valueOf(string(value, "kind")); }
        catch (IllegalArgumentException invalid) { throw new JSONException("Unsupported activity kind"); }
        long time = RemoteProtocol.integer(value, "observedAt");
        long minutes = RemoteProtocol.integer(value, "limitMinutes");
        if (time <= 0 || minutes < 0 || minutes > 1440) throw new JSONException("Invalid activity values");
        String zone = string(value, "zone"), day = string(value, "day");
        if (zone.length() > 80 || day.length() > 32) throw new JSONException("Invalid activity clock");
        try {
            if (!Instant.ofEpochMilli(time).atZone(ZoneId.of(zone)).toLocalDate().toString().equals(day))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new JSONException("Invalid activity clock"); }
        String configuration = string(value, "configuration");
        boolean limit = kind == ActivityEvent.Kind.APP_LIMIT_REACHED || kind == ActivityEvent.Kind.SHARED_LIMIT_REACHED;
        if (limit ? !configuration.matches("[0-9a-f]{64}") : !configuration.isEmpty())
            throw new JSONException("Invalid activity configuration");
        try {
            return new ActivityEvent(id, kind, time, string(value, "packageName"),
                    RemoteProtocol.integer(value, "amountCents"), string(value, "currency"),
                    string(value, "reason"), (int) minutes);
        } catch (IllegalArgumentException invalid) { throw new JSONException("Invalid activity fact"); }
    }

    private static String string(JSONObject value, String key) throws JSONException {
        Object field = value.get(key);
        if (!(field instanceof String)) throw new JSONException("Invalid activity field");
        return (String) field;
    }
}
