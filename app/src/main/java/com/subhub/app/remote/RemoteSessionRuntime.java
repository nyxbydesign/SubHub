package com.subhub.app.remote;

import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Current-peer session reviews, durable command results and receiver-owned native application. */
public final class RemoteSessionRuntime {
    public static final int HARDCORE_START_CONSENT_VERSION = 1;
    private static final String PROCESS = java.util.UUID.randomUUID().toString();
    private RemoteSessionRuntime() { }

    public enum Direction { OUTGOING, INCOMING }
    public enum Readiness { APP_MODE_READY, NEEDS_SUB_ACTION, UNAVAILABLE }

    /** Immutable UI projection. Its availability flags are never receiver authorization. */
    public static final class Snapshot {
        public final String peer, generation, idleFence, sessionIdentity, sourceWitness;
        public final boolean supported, canStart, canStop;
        public final long appliedRevision;
        public final RemoteSessionCommands.Duration service;
        public final Readiness readiness;
        public final RemoteSessionCommands.Reason reason;
        public final Record latestRecord;
        public final RemoteSessionCommands.Result latestResult;
        Snapshot(String peer, String generation, boolean supported, boolean canStart, boolean canStop,
                long appliedRevision, RemoteSessionCommands.Duration service, String idleFence,
                String sessionIdentity, Readiness readiness, RemoteSessionCommands.Reason reason,
                String sourceWitness, Record latestRecord) {
            this.peer = RemoteProtocol.peerId(peer); this.generation = RemoteProtocol.identifier(generation);
            this.supported = supported; this.canStart = canStart; this.canStop = canStop;
            this.appliedRevision = appliedRevision; this.service = service;
            this.idleFence = Objects.requireNonNull(idleFence); this.sessionIdentity = Objects.requireNonNull(sessionIdentity);
            this.readiness = Objects.requireNonNull(readiness); this.reason = Objects.requireNonNull(reason);
            this.sourceWitness = Objects.requireNonNull(sourceWitness); this.latestRecord = latestRecord;
            this.latestResult = latestRecord == null ? null : latestRecord.result;
        }
        static Snapshot unsupported(RemoteStore.Peer peer) {
            return new Snapshot(peer.identity, peer.generation, false, false, false, 0, null, "", "",
                    Readiness.UNAVAILABLE, RemoteSessionCommands.Reason.UNSUPPORTED, "", null);
        }
    }

    /** An exact local review; confirmation still revalidates its source and current keyholder. */
    public static final class Review {
        public final RemoteSessionCommands.Request request;
        public final String requestDigest, sourceWitness;
        public final long preparedAt;
        Review(RemoteSessionCommands.Request request, String sourceWitness, long preparedAt) throws JSONException {
            this.request = Objects.requireNonNull(request); this.requestDigest = request.digest();
            this.sourceWitness = Objects.requireNonNull(sourceWitness); this.preparedAt = preparedAt;
        }
    }

    /** A receiver-owned compare-on-accept review, never a credential accepted from a peer. */
    public static final class HardcoreReview {
        public final String dom, recipient, generation, domName, policyDigest;
        public final int consentVersion;
        public final long policyRevision, createdAt;
        public final long preparedElapsed;
        public final int boot;
        public final String reviewId;
        private final String process = PROCESS;
        HardcoreReview(String dom, String recipient, String generation, String domName,
                String policyDigest, long policyRevision, long createdAt) {
            this(dom, recipient, generation, domName, policyDigest, policyRevision,
                    new RemoteSessionClock.Sample(createdAt, -1, -1));
        }
        HardcoreReview(String dom, String recipient, String generation, String domName,
                String policyDigest, long policyRevision, RemoteSessionClock.Sample clock) {
            this.dom = RemoteProtocol.peerId(dom); this.recipient = RemoteProtocol.peerId(recipient);
            this.generation = RemoteProtocol.identifier(generation); this.domName = Objects.requireNonNull(domName);
            RemoteSessionCommands.digest(policyDigest); this.policyDigest = policyDigest;
            if (dom.equals(recipient)) throw new IllegalArgumentException("A peer is required");
            this.policyRevision = policyRevision; this.createdAt = clock.wall;
            this.preparedElapsed = clock.elapsed; this.boot = clock.boot;
            this.reviewId = java.util.UUID.randomUUID().toString();
            this.consentVersion = HARDCORE_START_CONSENT_VERSION;
        }
        public boolean currentProcess() { return PROCESS.equals(process); }
    }

    /** A native observation captured outside consent/authority locks; never decoded from the wire. */
    public static final class SystemObservation {
        public final String self;
        public final boolean adminActive;
        public final RemoteSessionClock.Sample clock;
        SystemObservation(String self, boolean adminActive, RemoteSessionClock.Sample clock) {
            this.self = RemoteProtocol.peerId(self); this.adminActive = adminActive; this.clock = Objects.requireNonNull(clock);
        }
    }

    static HardcoreReview reviewHardcore(android.content.Context context, RemoteStore store, String self,
            RemoteSessionClock.Sample now) throws JSONException {
        com.subhub.app.security.SubFeaturePolicy.registerHardcoreAuthority(context, store);
        synchronized (com.subhub.app.security.SubFeaturePolicy.LOCK) {
            synchronized (store) {
                requireSubWorkspace(context);
                if (now.wall <= 0 || now.elapsed < 0 || now.boot < 0) throw new IllegalStateException("Local clock unavailable");
                RemoteStore.Peer peer = assignedDom(store);
                if (peer == null) return null;
                return new HardcoreReview(peer.identity, self, peer.generation, peer.name,
                        com.subhub.app.security.SubFeaturePolicy.hardcoreReviewDigest(context),
                        com.subhub.app.security.SubFeaturePolicy.hardcoreReviewRevision(context), now);
            }
        }
    }

    static RemoteStore.Peer assignedDom(RemoteStore store) {
        synchronized (store) {
            RemoteStore.Peer found = null;
            for (RemoteStore.Peer peer : store.peers(false)) {
                if (peer.localRole != RemoteProtocol.Role.SUB || peer.state != RemoteStore.State.ACTIVE) continue;
                if (found != null) throw new IllegalStateException("Ambiguous Dom authority");
                found = peer;
            }
            return found;
        }
    }

    private static void requireSubWorkspace(android.content.Context context) {
        if (!com.subhub.app.security.DeviceRolePreferences.supportsSub(context)
                || com.subhub.app.security.DeviceRolePreferences.isDomWorkspace(context))
            throw new SecurityException("Local Sub review required");
    }

    /** Stored locally after the Sub reviews current Hardcore consent; never accepted from a peer. */
    public static final class HardcoreConsent {
        public final String dom, recipient, generation;
        public final long acceptedAt;
        public HardcoreConsent(String dom, String recipient, String generation, long acceptedAt) {
            this.dom = RemoteProtocol.peerId(dom); this.recipient = RemoteProtocol.peerId(recipient);
            this.generation = RemoteProtocol.identifier(generation); this.acceptedAt = acceptedAt;
            if (dom.equals(recipient) || acceptedAt <= 0) throw new IllegalArgumentException("Invalid local consent");
        }
        public JSONObject json() throws JSONException {
            return new JSONObject().put("version", HARDCORE_START_CONSENT_VERSION).put("dom", dom)
                    .put("recipient", recipient).put("generation", generation).put("acceptedAt", acceptedAt);
        }
        public static HardcoreConsent parseStored(JSONObject value) throws JSONException {
            RemoteSessionCommands.keys(value, "version", "dom", "recipient", "generation", "acceptedAt");
            if (RemoteSessionCommands.integer(value, "version") != HARDCORE_START_CONSENT_VERSION)
                throw new JSONException("Unsupported Hardcore consent version");
            return new HardcoreConsent(RemoteSessionCommands.text(value, "dom"),
                    RemoteSessionCommands.text(value, "recipient"), RemoteSessionCommands.text(value, "generation"),
                    RemoteSessionCommands.integer(value, "acceptedAt"));
        }
        public boolean matches(RemoteSessionCommands.Request request) {
            return dom.equals(request.sender) && recipient.equals(request.recipient)
                    && generation.equals(request.generation);
        }
    }

    /** A receiver-side observation from trusted local providers, not an authorization credential. */
    static final class Facts {
        final String self, peer, generation, idleFence, sessionIdentity;
        final RemoteProtocol.Role localRole;
        final boolean authenticated, activeRelationship, supportsSub, runtimeActive, hardcoreEnabled;
        final int negotiatedVersion;
        final long appliedRevision;
        final RemoteSessionCommands.Duration service;
        final HardcoreConsent consent;
        final Readiness readiness;
        Facts(String self, String peer, String generation, RemoteProtocol.Role localRole,
                boolean authenticated, boolean activeRelationship, boolean supportsSub, int negotiatedVersion,
                long appliedRevision, boolean runtimeActive, String idleFence, String sessionIdentity,
                RemoteSessionCommands.Duration service, boolean hardcoreEnabled, HardcoreConsent consent,
                Readiness readiness) {
            this.self = RemoteProtocol.peerId(self); this.peer = RemoteProtocol.peerId(peer);
            this.generation = RemoteProtocol.identifier(generation); this.localRole = Objects.requireNonNull(localRole);
            this.authenticated = authenticated; this.activeRelationship = activeRelationship; this.supportsSub = supportsSub;
            this.negotiatedVersion = negotiatedVersion; this.appliedRevision = appliedRevision;
            this.runtimeActive = runtimeActive; this.idleFence = Objects.requireNonNull(idleFence);
            this.sessionIdentity = Objects.requireNonNull(sessionIdentity); this.service = service;
            this.hardcoreEnabled = hardcoreEnabled; this.consent = consent; this.readiness = Objects.requireNonNull(readiness);
        }
    }

    public static final class Record {
        public final Direction direction;
        public final RemoteSessionCommands.Request request;
        public final RemoteSessionCommands.Result result;
        public final RemoteSessionClock.Bounds lifetime;
        Record(Direction direction, RemoteSessionCommands.Request request, RemoteSessionCommands.Result result,
                RemoteSessionClock.Bounds lifetime) {
            this.direction = Objects.requireNonNull(direction); this.request = Objects.requireNonNull(request);
            this.result = Objects.requireNonNull(result);
            this.lifetime = Objects.requireNonNull(lifetime);
        }
    }

    /** The facade advertises v1; current authority, local consent and native readiness still gate every effect. */
    public static boolean applicationEnabled() { return true; }

    static RemoteSessionCommands.Reason validateIncoming(RemoteSessionCommands.Request request, Facts facts, long now)
            throws JSONException {
        if (!facts.authenticated || !facts.activeRelationship || !facts.supportsSub
                || facts.localRole != RemoteProtocol.Role.SUB || !facts.peer.equals(request.sender)
                || !facts.generation.equals(request.generation)) return RemoteSessionCommands.Reason.AUTHORITY_CHANGED;
        if (!facts.self.equals(request.recipient)) return RemoteSessionCommands.Reason.WRONG_RECIPIENT;
        if (facts.negotiatedVersion != RemoteSessionCommands.VERSION) return RemoteSessionCommands.Reason.UNSUPPORTED;
        if (now <= 0 || request.issuedAt - now > RemoteSessionCommands.CLOCK_SKEW_MILLIS)
            return RemoteSessionCommands.Reason.CLOCK_CHANGED;
        if (now >= request.expiresAt) return RemoteSessionCommands.Reason.EXPIRED;
        if (request.action == RemoteSessionCommands.Action.STOP) {
            // The current linked Dom may stop its observed session even when Hardcore is off.
            return facts.runtimeActive && request.sessionIdentity.equals(facts.sessionIdentity)
                    ? RemoteSessionCommands.Reason.NONE : RemoteSessionCommands.Reason.SESSION_CHANGED;
        }
        if (!facts.hardcoreEnabled) return RemoteSessionCommands.Reason.HARDCORE_REQUIRED;
        if (facts.consent == null || facts.consent.acceptedAt > now || !facts.consent.matches(request))
            return RemoteSessionCommands.Reason.CONSENT_REQUIRED;
        if (request.appliedRevision != facts.appliedRevision || facts.service == null
                || !RemoteProtocol.canonical(request.service.json()).equals(RemoteProtocol.canonical(facts.service.json())))
            return RemoteSessionCommands.Reason.REVISION_CHANGED;
        if (facts.runtimeActive || facts.idleFence.isEmpty() || !request.idleFence.equals(facts.idleFence))
            return RemoteSessionCommands.Reason.SESSION_CHANGED;
        return facts.readiness == Readiness.APP_MODE_READY ? RemoteSessionCommands.Reason.NONE
                : RemoteSessionCommands.Reason.READINESS_REQUIRED;
    }
}
