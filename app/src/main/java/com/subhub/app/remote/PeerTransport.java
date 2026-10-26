package com.subhub.app.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Transport identity is established by cryptography, independently of display names or UI roles. */
public interface PeerTransport extends AutoCloseable {
    String localIdentity();
    String inviteRoute() throws IOException;
    Connection connect(String expectedPeerIdentity, String route, long timeoutMillis) throws IOException;
    Connection accept(long timeoutMillis) throws IOException;
    @Override void close() throws IOException;

    interface Connection extends AutoCloseable {
        String authenticatedPeerIdentity();
        InputStream input() throws IOException;
        OutputStream output() throws IOException;
        void setReadTimeoutMillis(long timeoutMillis);
        boolean isDirect();
        @Override void close() throws IOException;
    }
}
