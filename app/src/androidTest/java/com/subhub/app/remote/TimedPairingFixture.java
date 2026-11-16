package com.subhub.app.remote;

/** Stages synthetic relationships through the same store transitions as live pairing. */
public final class TimedPairingFixture {
    private TimedPairingFixture() { }

    static PairingLifecycleClock.Source clock(PairingInvite invite) {
        FormalRelationshipClock.Sample sample = new FormalRelationshipClock.Sample(
                invite.expiresAt - PairingInvite.LIFETIME_MILLIS, 1000, 1);
        return new PairingLifecycleClock.Source() {
            @Override public FormalRelationshipClock.Sample observe() { return sample; }

            @Override public FormalRelationshipClock.Sample transition(FormalRelationshipClock.Sample observed) {
                return sample;
            }
        };
    }

    static void stageDom(RemoteStore store, PairingInvite invite, String candidate, String name, String route) {
        PairingLifecycleClock.Source clock = clock(invite);
        FormalRelationshipClock.Sample sample = clock.observe();
        store.saveTimedInvite(invite, sample, clock);
        store.bindTimedInvite(invite.id, candidate, invite.tokenHash(), sample, clock);
        approveDom(store, invite, candidate, name, route, clock);
    }

    static void approveDom(RemoteStore store, PairingInvite invite, String candidate,
            String name, String route, PairingLifecycleClock.Source clock) {
        store.describeCandidate(invite.id, candidate, name, route);
        store.approveTimedInvite(invite.id, candidate, invite.domIdentity,
                PairingInvite.fingerprint(invite.domIdentity, candidate, invite.id), clock.observe(), clock);
    }

    static void stageSub(RemoteStore store, PairingInvite invite, String name) {
        PairingLifecycleClock.Source clock = clock(invite);
        store.acceptTimedInvite(invite, name, clock.observe(), clock);
    }

    static void approveSub(RemoteStore store, PairingInvite invite, String localIdentity) {
        PairingLifecycleClock.Source clock = clock(invite);
        store.observeTimedApproval(store.peer(invite.domIdentity, invite.id), invite.tokenHash(), localIdentity,
                PairingInvite.fingerprint(invite.domIdentity, localIdentity, invite.id), clock.observe(), clock);
    }

    static RemoteStore.Peer activate(RemoteStore store, PairingInvite invite, String peer) {
        PairingLifecycleClock.Source clock = clock(invite);
        return store.activateTimedPeer(store.peer(peer, invite.id), invite.tokenHash(), clock.observe(), clock);
    }

    public static void activeDom(RemoteStore store, PairingInvite invite, String candidate, String name) {
        activeDom(store, invite, candidate, name, "");
    }

    public static void activeDom(RemoteStore store, PairingInvite invite, String candidate, String name, String route) {
        stageDom(store, invite, candidate, name, route);
        activate(store, invite, candidate);
    }

    public static void activeSub(RemoteStore store, PairingInvite invite, String localIdentity, String name) {
        stageSub(store, invite, name);
        approveSub(store, invite, localIdentity);
        activate(store, invite, invite.domIdentity);
    }
}
