package com.subhub.app.remote;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Versioned session-control facts. A decoded request is not permission to change runtime state. */
public final class RemoteSessionCommands {
    public static final int VERSION = 1;
    public static final long LIFETIME_MILLIS = 120_000;
    public static final long CLOCK_SKEW_MILLIS = 30_000;
    public static final long MIN_DURATION_MILLIS = 1_800_000;
    public static final long MAX_DURATION_MILLIS = 2_592_000_000L;
    private RemoteSessionCommands() { }

    public enum Action { START, STOP }
    public enum State {
        QUEUED, ACKNOWLEDGED, APPLYING, APPLIED, REJECTED, EXPIRED, NEEDS_SUB_ACTION, FAILED;
        public boolean terminal() {
            return this == APPLIED || this == REJECTED || this == EXPIRED
                    || this == NEEDS_SUB_ACTION || this == FAILED;
        }
    }
    public enum Reason {
        NONE, UNSUPPORTED, AUTHORITY_CHANGED, WRONG_RECIPIENT, REVISION_CHANGED,
        SESSION_CHANGED, HARDCORE_REQUIRED, CONSENT_REQUIRED, READINESS_REQUIRED,
        EXPIRED, CLOCK_CHANGED, APPLICATION_FAILED, PROCESS_RESTARTED
    }

    /** The same saved service selection used by the remote configuration editor. */
    public static final class Duration {
        public final long duration, minimum, maximum;
        public final boolean hidden;
        public Duration(long duration, long minimum, long maximum, boolean hidden) {
            if (duration != -1 && duration != -2 && duration != 3_600_000
                    && duration != 86_400_000 && duration != 604_800_000
                    && duration != MAX_DURATION_MILLIS)
                throw new IllegalArgumentException("Unsupported service duration");
            if (minimum < MIN_DURATION_MILLIS || maximum > MAX_DURATION_MILLIS || maximum < minimum)
                throw new IllegalArgumentException("Invalid service range");
            this.duration = duration; this.minimum = minimum; this.maximum = maximum; this.hidden = hidden;
        }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("duration", duration).put("minimum", minimum)
                    .put("maximum", maximum).put("hidden", hidden);
        }
        public static Duration parse(JSONObject value) throws JSONException {
            keys(value, "duration", "minimum", "maximum", "hidden");
            if (!(value.get("hidden") instanceof Boolean)) throw new JSONException("Invalid visibility");
            return new Duration(integer(value, "duration"), integer(value, "minimum"),
                    integer(value, "maximum"), value.getBoolean("hidden"));
        }
        public boolean permits(long selected) {
            return duration == -1 ? selected == -1
                    : duration == -2 ? selected >= minimum && selected <= maximum : selected == duration;
        }
    }

    public static final class Request {
        public final String id, sender, recipient, generation, idleFence, sessionIdentity;
        public final Action action;
        public final long sequence, appliedRevision, issuedAt, expiresAt;
        public final Duration service;
        private Request(String id, Action action, String sender, String recipient, String generation,
                long sequence, long appliedRevision, long issuedAt, long expiresAt, String idleFence,
                String sessionIdentity, Duration service) {
            this.id = RemoteProtocol.identifier(id); this.action = Objects.requireNonNull(action);
            this.sender = RemoteProtocol.peerId(sender); this.recipient = RemoteProtocol.peerId(recipient);
            this.generation = RemoteProtocol.identifier(generation);
            if (sender.equals(recipient)) throw new IllegalArgumentException("Session control requires a peer");
            if (sequence <= 0 || appliedRevision < 0 || issuedAt <= 0 || expiresAt <= issuedAt
                    || expiresAt - issuedAt > LIFETIME_MILLIS)
                throw new IllegalArgumentException("Invalid session-control bounds");
            if (action == Action.START) {
                RemoteSessionCommands.digest(idleFence); if (!sessionIdentity.isEmpty() || service == null)
                    throw new IllegalArgumentException("Invalid Start fence");
            } else {
                RemoteSessionCommands.digest(sessionIdentity); if (!idleFence.isEmpty() || service != null)
                    throw new IllegalArgumentException("Invalid Stop fence");
            }
            this.sequence = sequence; this.appliedRevision = appliedRevision; this.issuedAt = issuedAt; this.expiresAt = expiresAt;
            this.idleFence = idleFence; this.sessionIdentity = sessionIdentity; this.service = service;
        }
        public static Request start(String id, String sender, String recipient, String generation,
                long sequence, long revision, long issuedAt, long expiresAt, String idleFence, Duration service) {
            return new Request(id, Action.START, sender, recipient, generation, sequence, revision, issuedAt,
                    expiresAt, idleFence, "", service);
        }
        public static Request stop(String id, String sender, String recipient, String generation,
                long sequence, long revision, long issuedAt, long expiresAt, String sessionIdentity) {
            return new Request(id, Action.STOP, sender, recipient, generation, sequence, revision, issuedAt,
                    expiresAt, "", sessionIdentity, null);
        }
        public JSONObject json() throws JSONException {
            JSONObject value = new JSONObject().put("schema", VERSION).put("type", "session_control")
                    .put("action", action.name()).put("id", id).put("sender", sender)
                    .put("recipient", recipient).put("generation", generation).put("sequence", sequence)
                    .put("appliedRevision", appliedRevision).put("issuedAt", issuedAt).put("expiresAt", expiresAt);
            if (action == Action.START) value.put("idleFence", idleFence).put("service", service.json());
            else value.put("sessionIdentity", sessionIdentity);
            return value;
        }
        public String digest() throws JSONException {
            return RemoteProtocol.sha256(RemoteProtocol.canonical(json()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        public static Request parse(JSONObject value) throws JSONException {
            String action = text(value, "action");
            Set<String> fields = new HashSet<>(Arrays.asList("schema", "type", "action", "id", "sender",
                    "recipient", "generation", "sequence", "appliedRevision", "issuedAt", "expiresAt"));
            if ("START".equals(action)) fields.addAll(Arrays.asList("idleFence", "service"));
            else if ("STOP".equals(action)) fields.add("sessionIdentity");
            else throw new JSONException("Unsupported session action");
            keys(value, fields.toArray(new String[0]));
            if (integer(value, "schema") != VERSION || !"session_control".equals(text(value, "type")))
                throw new JSONException("Unsupported session-control schema");
            String id = text(value, "id"), sender = text(value, "sender"), recipient = text(value, "recipient"),
                    generation = text(value, "generation");
            long revision = integer(value, "appliedRevision"), issued = integer(value, "issuedAt"), expires = integer(value, "expiresAt");
            return "START".equals(action)
                    ? start(id, sender, recipient, generation, integer(value, "sequence"), revision, issued, expires,
                            text(value, "idleFence"), Duration.parse(value.getJSONObject("service")))
                    : stop(id, sender, recipient, generation, integer(value, "sequence"), revision, issued, expires, text(value, "sessionIdentity"));
        }
    }

    /** Observed application evidence, not a receipt or an instruction to create a session. */
    public static final class Proof {
        public final boolean active;
        public final String sessionIdentity;
        public final long startedAt, endsAt, selectedDuration;
        public Proof(boolean active, String sessionIdentity, long startedAt, long endsAt, long selectedDuration) {
            if (active) digest(sessionIdentity);
            else if (!sessionIdentity.isEmpty()) throw new IllegalArgumentException("Stopped runtime still has an identity");
            if (startedAt < 0 || endsAt < 0) throw new IllegalArgumentException("Invalid runtime clock");
            this.active = active; this.sessionIdentity = sessionIdentity; this.startedAt = startedAt;
            this.endsAt = endsAt; this.selectedDuration = selectedDuration;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("active", active).put("sessionIdentity", sessionIdentity)
                    .put("startedAt", startedAt).put("endsAt", endsAt).put("selectedDuration", selectedDuration);
        }
        static Proof parse(JSONObject value) throws JSONException {
            keys(value, "active", "sessionIdentity", "startedAt", "endsAt", "selectedDuration");
            if (!(value.get("active") instanceof Boolean)) throw new JSONException("Invalid runtime state");
            return new Proof(value.getBoolean("active"), text(value, "sessionIdentity"), integer(value, "startedAt"),
                    integer(value, "endsAt"), integer(value, "selectedDuration"));
        }
        void requireFor(Request request) {
            if (request.action == Action.STOP) {
                if (active || startedAt != 0 || endsAt != 0 || selectedDuration != 0)
                    throw new IllegalArgumentException("Stop has no observed stopped runtime");
            } else if (!active || startedAt <= 0 || !request.service.permits(selectedDuration)
                    || (selectedDuration == -1 ? endsAt != 0
                    : selectedDuration <= 0 || startedAt > Long.MAX_VALUE - selectedDuration
                            || endsAt != startedAt + selectedDuration)) {
                throw new IllegalArgumentException("Start lacks matching runtime evidence");
            }
        }
    }

    public static final class Result {
        public final String id, requestDigest;
        public final State state;
        public final Reason reason;
        public final long observedAt;
        public final Proof proof;
        public Result(Request request, State state, Reason reason, long observedAt, Proof proof) throws JSONException {
            this.id = request.id; this.requestDigest = request.digest(); this.state = Objects.requireNonNull(state);
            this.reason = Objects.requireNonNull(reason); this.observedAt = observedAt; this.proof = proof;
            if (observedAt <= 0 && reason != Reason.CLOCK_CHANGED)
                throw new IllegalArgumentException("Missing result clock");
            boolean failure = state.terminal() && state != State.APPLIED;
            if (failure == (reason == Reason.NONE)) throw new IllegalArgumentException("Invalid result reason");
            if (state == State.APPLIED) {
                if (proof == null) throw new IllegalArgumentException("Applied requires native evidence");
                proof.requireFor(request);
            } else if (proof != null) throw new IllegalArgumentException("Receipt is not application evidence");
        }
        public JSONObject json() throws JSONException {
            JSONObject value = new JSONObject().put("schema", VERSION).put("id", id).put("requestDigest", requestDigest)
                    .put("state", state.name()).put("reason", reason.name()).put("observedAt", observedAt);
            if (proof != null) value.put("proof", proof.json());
            return value;
        }
        public static Result parse(Request request, JSONObject value) throws JSONException {
            keys(value, value.has("proof")
                    ? new String[]{"schema", "id", "requestDigest", "state", "reason", "observedAt", "proof"}
                    : new String[]{"schema", "id", "requestDigest", "state", "reason", "observedAt"});
            if (integer(value, "schema") != VERSION || !request.id.equals(text(value, "id"))
                    || !request.digest().equals(text(value, "requestDigest")))
                throw new JSONException("Session result identity mismatch");
            return new Result(request, State.valueOf(text(value, "state")), Reason.valueOf(text(value, "reason")),
                    integer(value, "observedAt"), value.has("proof") ? Proof.parse(value.getJSONObject("proof")) : null);
        }
        /** Receipts may be skipped, but a terminal result is immutable, including its evidence. */
        public boolean mayFollow(Result previous) throws JSONException {
            if (!id.equals(previous.id) || !requestDigest.equals(previous.requestDigest)) return false;
            if (previous.state.terminal() || state == previous.state)
                return RemoteProtocol.canonical(json()).equals(RemoteProtocol.canonical(previous.json()));
            // QUEUED and receiver results use different device clocks; eligibility uses local journal bounds.
            return state.terminal() || rank(state) > rank(previous.state);
        }
    }

    public static int negotiatedVersion(JSONObject frame) throws JSONException {
        if (!frame.has("sessionControlVersion")) return 0;
        long version = integer(frame, "sessionControlVersion");
        if (version <= 0) throw new JSONException("Invalid session-control capability");
        return version == VERSION ? VERSION : 0;
    }
    static int rank(State state) {
        return state == State.QUEUED ? 0 : state == State.ACKNOWLEDGED ? 1 : state == State.APPLYING ? 2 : 3;
    }
    static long integer(JSONObject value, String key) throws JSONException { return RemoteProtocol.integer(value, key); }
    static String text(JSONObject value, String key) throws JSONException {
        Object field = value.get(key); if (!(field instanceof String)) throw new JSONException("Invalid text field");
        return (String) field;
    }
    static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid runtime fence");
    }
    static void keys(JSONObject value, String... expected) throws JSONException {
        Set<String> actual = new HashSet<>(); value.keys().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw new JSONException("Invalid session-control fields");
    }
}
