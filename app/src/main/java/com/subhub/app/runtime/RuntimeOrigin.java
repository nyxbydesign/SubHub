package com.subhub.app.runtime;

import java.util.Objects;

/** Initiation evidence is distinct from the physical target and never grants authority by itself. */
public final class RuntimeOrigin {
    public enum Kind { LOCAL_UI, REMOTE }
    public static final RuntimeOrigin LOCAL_UI = new RuntimeOrigin(null);
    public final Kind kind;
    public final RemoteProof remote;

    private RuntimeOrigin(RemoteProof remote) {
        this.remote = remote; kind = remote == null ? Kind.LOCAL_UI : Kind.REMOTE;
    }
    public static RuntimeOrigin remote(RemoteProof proof) { return new RuntimeOrigin(Objects.requireNonNull(proof)); }

    public static final class RemoteProof {
        public final String peer, relationshipGeneration, commandId, commandDigest, preparedDigest;
        public final String statisticsIdentity, pactIdentity, captureIdentity;
        // Revisions are opaque equality tokens. Their complete signed-long domain is retained.
        public final long authorizationRevision;
        public RemoteProof(String peer, String relationshipGeneration, String commandId,
                String commandDigest, String preparedDigest, long authorizationRevision,
                String statisticsIdentity, String pactIdentity, String captureIdentity) {
            this.peer = digest(peer); this.relationshipGeneration = key(relationshipGeneration);
            this.commandId = uuid(commandId); this.commandDigest = digest(commandDigest);
            this.preparedDigest = digest(preparedDigest); this.authorizationRevision = authorizationRevision;
            this.statisticsIdentity = component(statisticsIdentity);
            this.pactIdentity = component(pactIdentity); this.captureIdentity = component(captureIdentity);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof RemoteProof)) return false;
            RemoteProof value = (RemoteProof) other;
            return authorizationRevision == value.authorizationRevision && peer.equals(value.peer)
                    && relationshipGeneration.equals(value.relationshipGeneration) && commandId.equals(value.commandId)
                    && commandDigest.equals(value.commandDigest) && preparedDigest.equals(value.preparedDigest)
                    && statisticsIdentity.equals(value.statisticsIdentity) && pactIdentity.equals(value.pactIdentity)
                    && captureIdentity.equals(value.captureIdentity);
        }
        @Override public int hashCode() { return Objects.hash(peer, relationshipGeneration, commandId,
                commandDigest, preparedDigest, authorizationRevision, statisticsIdentity, pactIdentity, captureIdentity); }
    }

    static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid runtime identity");
        return value;
    }
    static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Invalid runtime key");
        return value;
    }
    private static String digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid receipt digest");
        return value;
    }
    private static String component(String value) {
        return "".equals(value) ? "" : uuid(value);
    }
    @Override public boolean equals(Object other) {
        return other instanceof RuntimeOrigin && kind == ((RuntimeOrigin) other).kind
                && Objects.equals(remote, ((RuntimeOrigin) other).remote);
    }
    @Override public int hashCode() { return Objects.hash(kind, remote); }
}
