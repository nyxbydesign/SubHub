package com.subhub.app.remote;

import org.json.JSONException;
import org.json.JSONObject;

/** Content identity excludes sampling time only for the current runtime snapshot. */
final class RemoteSyncContent {
    private RemoteSyncContent() { }

    static boolean same(RemoteProtocol.Stream stream, String key, String left, String right) {
        if (left.equals(right)) return true;
        if (stream != RemoteProtocol.Stream.RUNTIME || !"current".equals(key)) return false;
        try {
            JSONObject previous = new JSONObject(left), current = new JSONObject(right);
            previous.remove("observedAt");
            current.remove("observedAt");
            // Stream-head confirmation supplies freshness without resending unchanged state.
            return RemoteProtocol.canonical(previous).equals(RemoteProtocol.canonical(current));
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid runtime content", invalid);
        }
    }
}
