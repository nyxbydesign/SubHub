package com.subhub.app.remote;

/** Presence is a recent successful contact, not a guarantee of continuous reachability. */
public final class PeerPresence {
    public static final long RECENT_CONTACT_MILLIS = 90_000L;
    private PeerPresence() {}

    public static boolean online(boolean connected, long lastContact, long now) {
        return connected || lastContact > 0 && now >= lastContact
                && now - lastContact <= RECENT_CONTACT_MILLIS;
    }
}
