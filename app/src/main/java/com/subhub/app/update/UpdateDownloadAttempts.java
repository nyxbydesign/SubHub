package com.subhub.app.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/** Bounds automatic starts of the same APK independently of release check cadence. */
final class UpdateDownloadAttempts {
    private static final int MAX_ASSETS = 32;
    private static final long FIRST_RETRY = TimeUnit.HOURS.toMillis(1);
    private static final long SECOND_RETRY = TimeUnit.HOURS.toMillis(6);

    private UpdateDownloadAttempts() { }

    /** Returns a new ledger to persist before enqueue, or null when this asset must wait. */
    static String reserve(String encoded, String sha256, long now) throws JSONException {
        JSONArray previous = new JSONArray(encoded);
        int attempts = 0;
        for (int index = 0; index < previous.length(); index++) {
            JSONObject entry = previous.getJSONObject(index);
            if (!sha256.equals(entry.getString("sha256"))) continue;
            attempts = entry.getInt("attempts");
            if (attempts < 1 || attempts >= 3) return null;
            long interval = attempts == 1 ? FIRST_RETRY : SECOND_RETRY;
            long startedAt = entry.getLong("startedAt");
            if (now < startedAt || now - startedAt < interval) return null;
            break;
        }
        JSONArray next = new JSONArray().put(new JSONObject().put("sha256", sha256)
                .put("attempts", attempts + 1).put("startedAt", now));
        for (int index = 0; index < previous.length() && next.length() < MAX_ASSETS; index++) {
            JSONObject entry = previous.getJSONObject(index);
            if (!sha256.equals(entry.getString("sha256"))) next.put(entry);
        }
        return next.toString();
    }
}
