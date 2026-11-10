package com.subhub.app.remote;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Negotiated advisory state. Eligibility and freshness never authorize native application. */
final class RemoteSessionControlState {
    static final int MAX_BYTES = 4096;
    static final String METADATA = "session_control_state_v1";
    private static final long FRESH_MILLIS = 90_000;
    final String sender, recipient, generation, idleFence, sessionIdentity;
    final long observedAt, appliedRevision;
    final RemoteSessionCommands.Duration service;
    final boolean active, canStart, canStop;
    final RemoteSessionRuntime.Readiness readiness;
    final RemoteSessionCommands.Reason reason;

    RemoteSessionControlState(String sender, String recipient, String generation, long observedAt, long appliedRevision,
            RemoteSessionCommands.Duration service, boolean active, String idleFence, String sessionIdentity,
            RemoteSessionRuntime.Readiness readiness, RemoteSessionCommands.Reason reason, boolean canStart, boolean canStop) {
        this.sender = RemoteProtocol.peerId(sender); this.recipient = RemoteProtocol.peerId(recipient);
        this.generation = RemoteProtocol.identifier(generation);
        if (sender.equals(recipient) || observedAt <= 0 || appliedRevision < 0) throw new IllegalArgumentException("Invalid state scope");
        Objects.requireNonNull(idleFence); Objects.requireNonNull(sessionIdentity);
        if (active) {
            if (!idleFence.isEmpty()) throw new IllegalArgumentException("Active state contains an idle fence");
            if (!sessionIdentity.isEmpty()) RemoteSessionCommands.digest(sessionIdentity);
        } else {
            RemoteSessionCommands.digest(idleFence);
            if (!sessionIdentity.isEmpty()) throw new IllegalArgumentException("Inactive state contains a session");
        }
        if (canStart && (active || service == null || readiness != RemoteSessionRuntime.Readiness.APP_MODE_READY
                || reason != RemoteSessionCommands.Reason.NONE) || canStop && (!active || sessionIdentity.isEmpty()))
            throw new IllegalArgumentException("Inconsistent state eligibility");
        this.observedAt = observedAt; this.appliedRevision = appliedRevision; this.service = service;
        this.active = active; this.idleFence = idleFence; this.sessionIdentity = sessionIdentity;
        this.readiness = Objects.requireNonNull(readiness); this.reason = Objects.requireNonNull(reason);
        this.canStart = canStart; this.canStop = canStop;
    }
    JSONObject json() throws JSONException {
        JSONObject value = new JSONObject().put("schema", 1).put("sender", sender).put("recipient", recipient)
                .put("generation", generation).put("observedAt", observedAt).put("appliedRevision", appliedRevision)
                .put("service", service == null ? JSONObject.NULL : service.json()).put("active", active)
                .put("idleFence", idleFence).put("sessionIdentity", sessionIdentity).put("readiness", readiness.name())
                .put("reason", reason.name()).put("canStart", canStart).put("canStop", canStop);
        bounded(value); return value;
    }
    static RemoteSessionControlState parse(JSONObject value) throws JSONException {
        bounded(value);
        RemoteSessionCommands.keys(value, "schema", "sender", "recipient", "generation", "observedAt", "appliedRevision",
                "service", "active", "idleFence", "sessionIdentity", "readiness", "reason", "canStart", "canStop");
        if (RemoteProtocol.integer(value, "schema") != 1) throw new JSONException("Unsupported session state");
        return new RemoteSessionControlState(text(value, "sender"), text(value, "recipient"), text(value, "generation"),
                RemoteProtocol.integer(value, "observedAt"), RemoteProtocol.integer(value, "appliedRevision"),
                value.isNull("service") ? null : RemoteSessionCommands.Duration.parse(value.getJSONObject("service")),
                bool(value, "active"), text(value, "idleFence"), text(value, "sessionIdentity"),
                RemoteSessionRuntime.Readiness.valueOf(text(value, "readiness")),
                RemoteSessionCommands.Reason.valueOf(text(value, "reason")), bool(value, "canStart"), bool(value, "canStop"));
    }
    /** Repeated observations of unchanged facts do not invalidate an open local review. */
    String witness() throws JSONException {
        JSONObject value = json(); value.remove("observedAt");
        return RemoteProtocol.sha256(RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8));
    }
    private static String text(JSONObject value, String key) throws JSONException { return RemoteSessionCommands.text(value, key); }
    private static boolean bool(JSONObject value, String key) throws JSONException {
        Object field = value.get(key); if (!(field instanceof Boolean)) throw new JSONException("Invalid state flag"); return (Boolean) field;
    }
    private static void bounded(JSONObject value) throws JSONException {
        if (RemoteProtocol.canonical(value).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new JSONException("Session state too large");
    }

    /** Receiver-owned age anchor; a remote clock cannot extend this freshness budget. */
    static final class Received {
        final RemoteSessionControlState state;
        final RemoteSessionClock.Sample clock;
        Received(RemoteSessionControlState state, RemoteSessionClock.Sample clock) {
            this.state = Objects.requireNonNull(state); this.clock = Objects.requireNonNull(clock);
            if (clock.wall <= 0 || clock.elapsed < 0 || clock.boot < 0) throw new IllegalArgumentException("Local state clock unavailable");
        }
        boolean fresh(RemoteSessionClock.Sample now) {
            if (now.boot != clock.boot || now.wall < clock.wall || now.elapsed < clock.elapsed) return false;
            long wall = now.wall - clock.wall, elapsed = now.elapsed - clock.elapsed;
            return elapsed < FRESH_MILLIS && Math.abs(wall - elapsed) <= 2000;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("schema", 1).put("state", state.json()).put("receivedWall", clock.wall)
                    .put("receivedElapsed", clock.elapsed).put("boot", clock.boot);
        }
        static Received parse(JSONObject value) throws JSONException {
            RemoteSessionCommands.keys(value, "schema", "state", "receivedWall", "receivedElapsed", "boot");
            long boot = RemoteProtocol.integer(value, "boot");
            if (RemoteProtocol.integer(value, "schema") != 1 || boot < 0 || boot > Integer.MAX_VALUE)
                throw new JSONException("Invalid received state clock");
            return new Received(RemoteSessionControlState.parse(value.getJSONObject("state")),
                    new RemoteSessionClock.Sample(RemoteProtocol.integer(value, "receivedWall"),
                            RemoteProtocol.integer(value, "receivedElapsed"), (int) boot));
        }
    }
}
