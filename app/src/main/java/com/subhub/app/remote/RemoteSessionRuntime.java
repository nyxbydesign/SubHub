package com.subhub.app.remote;

import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Admission and journal vocabulary only; this version has no session-application implementation. */
public final class RemoteSessionRuntime {
    public static final int HARDCORE_START_CONSENT_VERSION = 1;
    private RemoteSessionRuntime() { }

    public enum Direction { OUTGOING, INCOMING }
    public enum Readiness { APP_MODE_READY, NEEDS_SUB_ACTION, UNAVAILABLE }

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
        Record(Direction direction, RemoteSessionCommands.Request request, RemoteSessionCommands.Result result) {
            this.direction = Objects.requireNonNull(direction); this.request = Objects.requireNonNull(request);
            this.result = Objects.requireNonNull(result);
        }
    }

    /** No production caller may mistake the inactive codec/storage checkpoint for supported Start. */
    public static boolean applicationEnabled() { return false; }

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
