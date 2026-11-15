package com.subhub.app.remote;

import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Recent authenticated foreground, opted-in background, or application activity. */
public final class PeerPresence {
    public static final long RECENT_CONTACT_MILLIS = 90_000L;
    static final long RECENT_DATA_MILLIS = 30_000L;
    // Tolerate ordinary foreground sync retries without keeping an unreachable peer Idle indefinitely.
    static final long RECENT_BACKGROUND_MILLIS = 120_000L;
    private static final String PROCESS = UUID.randomUUID().toString();
    private static final String KEY = "foreground_presence_v1";
    private PeerPresence() { }

    public enum Status { OFFLINE, IDLE, ONLINE }

    public static final class Observation {
        public final Status status;
        public final boolean online;
        public final boolean backgroundObserved;
        public final long lastOnlineAt;
        public final long expiresInMillis;

        private Observation(Status status, boolean backgroundObserved, long lastOnlineAt, long expiresInMillis) {
            this.status = status;
            this.online = status == Status.ONLINE;
            this.backgroundObserved = backgroundObserved;
            this.lastOnlineAt = lastOnlineAt;
            this.expiresInMillis = expiresInMillis;
        }
    }

    /** A live socket and last transport contact are deliberately insufficient. */
    public static boolean online(boolean foreground,long observedElapsed,long nowElapsed) {
        return foreground && remaining(observedElapsed, nowElapsed, RECENT_CONTACT_MILLIS) > 0;
    }

    public static Observation read(RemoteStore store,RemoteStore.Peer peer) {
        synchronized(store) {
            RemoteStore.Peer current=store.peer(peer.identity,peer.generation);
            if(current==null || current.state!=RemoteStore.State.ACTIVE)return new Observation(Status.OFFLINE, false, 0, 0);
            return decode(store.metadata(peer.identity,peer.generation,KEY,""),System.currentTimeMillis(),
                    SystemClock.elapsedRealtime(),PROCESS);
        }
    }

    static Observation decode(String value, long nowWall, long nowElapsed, String process) {
        JSONObject saved = parse(value);
        long last = lastSeen(saved, nowWall);
        boolean backgroundObserved = saved.opt("backgroundSync") instanceof Boolean;
        if (!process.equals(saved.optString("process")))
            return new Observation(Status.OFFLINE, backgroundObserved, last, 0);
        if (backgroundObserved && !saved.optBoolean("foreground")) {
            long idle = Boolean.TRUE.equals(saved.opt("backgroundSync")) ? backgroundRemaining(saved, nowElapsed) : 0;
            return new Observation(idle > 0 ? Status.IDLE : Status.OFFLINE, true, last, idle);
        }
        long foreground = saved.optBoolean("foreground")
                ? remaining(saved.optLong("receivedElapsed", -1), nowElapsed, RECENT_CONTACT_MILLIS) : 0;
        long data = remaining(saved.optLong("dataElapsed", -1), nowElapsed, RECENT_DATA_MILLIS);
        long expires = last > 0 ? Math.max(foreground, data) : 0;
        return new Observation(expires > 0 ? Status.ONLINE : Status.OFFLINE, backgroundObserved, last, expires);
    }

    private static long backgroundRemaining(JSONObject saved, long nowElapsed) {
        return remaining(saved.optLong("receivedElapsed", -1), nowElapsed, RECENT_BACKGROUND_MILLIS);
    }

    private static long remaining(long observed, long now, long duration) {
        return observed >= 0 && now >= observed ? Math.max(0, duration - (now - observed)) : 0;
    }

    static boolean receive(RemoteStore store, RemoteStore.Peer peer, JSONObject payload) throws JSONException {
        if (RemoteProtocol.integer(payload, "version") != 1) throw new JSONException("Unsupported presence");
        long sequence = RemoteProtocol.integer(payload, "sequence");
        Object flag = payload.get("foreground");
        Object background = payload.opt("backgroundSync");
        if (sequence <= 0 || !(flag instanceof Boolean) || background != null && !(background instanceof Boolean))
            throw new JSONException("Invalid presence");
        return store.authorityAccess(() -> {
            synchronized (store) {
                JSONObject saved = currentRecord(store, peer);
                if (!recordForeground(saved, sequence, (Boolean) flag, (Boolean) background, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), PROCESS)) return false;
                store.putMetadata(peer.identity, peer.generation, KEY, saved.toString());
                return true;
            }
        });
    }

    /** Call only after new data is durably accepted from the exact authenticated relationship. */
    static boolean receiveData(RemoteStore store, RemoteStore.Peer peer) {
        return store.authorityAccess(() -> {
            synchronized (store) {
                JSONObject saved = currentRecord(store, peer);
                if (!recordData(saved, System.currentTimeMillis(), SystemClock.elapsedRealtime(), PROCESS)) return false;
                store.putMetadata(peer.identity, peer.generation, KEY, saved.toString());
                return true;
            }
        });
    }

    static boolean recordForeground(JSONObject saved, long sequence, boolean foreground, long nowWall,
            long nowElapsed, String process) throws JSONException {
        return recordForeground(saved, sequence, foreground, null, nowWall, nowElapsed, process);
    }

    static boolean recordForeground(JSONObject saved, long sequence, boolean foreground, Boolean backgroundSync,
            long nowWall, long nowElapsed, String process) throws JSONException {
        if (sequence <= saved.optLong("sequence", 0)) return false;
        retainCurrentProcess(saved, process);
        saved.put("sequence", sequence).put("foreground", foreground).put("receivedElapsed", nowElapsed)
                .put("lastOnlineAt", foreground ? nowWall : lastSeen(saved, nowWall));
        if (backgroundSync == null) {
            saved.remove("backgroundSync");
        } else {
            saved.put("backgroundSync", backgroundSync);
            if (!foreground) saved.remove("dataElapsed");
        }
        return true;
    }

    static boolean recordData(JSONObject saved, long nowWall, long nowElapsed, String process) throws JSONException {
        retainCurrentProcess(saved, process);
        if (!saved.optBoolean("foreground") && saved.opt("backgroundSync") instanceof Boolean) {
            if (backgroundRemaining(saved, nowElapsed) > 0) return false;
            saved.remove("backgroundSync");
        }
        saved.put("dataElapsed", nowElapsed).put("lastOnlineAt", nowWall);
        return true;
    }

    private static void retainCurrentProcess(JSONObject saved, String process) throws JSONException {
        if (!process.equals(saved.optString("process"))) {
            saved.remove("dataElapsed");
            saved.remove("backgroundSync");
            saved.put("foreground", false).put("receivedElapsed", -1).put("process", process);
        }
    }

    private static long lastSeen(JSONObject saved, long nowWall) {
        long last = saved.optLong("lastOnlineAt", 0);
        return last > 0 && last <= nowWall ? last : 0;
    }

    private static JSONObject currentRecord(RemoteStore store, RemoteStore.Peer peer) {
        RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
        if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != peer.localRole)
            throw new SecurityException("Presence relationship changed");
        return parse(store.metadata(peer.identity, peer.generation, KEY, ""));
    }

    private static JSONObject parse(String value) {
        try { return new JSONObject(value); }
        catch (JSONException invalid) { return new JSONObject(); }
    }
}
