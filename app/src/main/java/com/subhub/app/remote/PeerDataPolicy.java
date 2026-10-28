package com.subhub.app.remote;

/** Configuration-only data boundary; retained monitoring outboxes remain private and unacknowledged. */
final class PeerDataPolicy {
    private PeerDataPolicy() { }
    static boolean permits(RemoteProtocol.Stream stream) {
        return stream == RemoteProtocol.Stream.CONFIGURATION
                || stream == RemoteProtocol.Stream.COMMAND_RESULT
                || stream == RemoteProtocol.Stream.BASELINE
                || stream == RemoteProtocol.Stream.APP_CATALOG
                || stream == RemoteProtocol.Stream.CONTRACTS;
    }
    static void require(RemoteProtocol.Stream stream) {
        if (!permits(stream)) throw new SecurityException("Peer monitoring data is unavailable");
    }
}
