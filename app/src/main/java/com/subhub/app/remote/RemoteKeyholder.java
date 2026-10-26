package com.subhub.app.remote;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.provider.Settings;

import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.Totp;

import java.security.GeneralSecurityException;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Offline controller credentials belong to one approved peer generation and never enter sync mirrors. */
public final class RemoteKeyholder implements KeyholderProvider {
    public static final class Code {
        public final String value;
        public final long expiresAt;
        private Code(String value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    private static final String PREFIX = "controller_auth_v1_";
    private final Context context;
    private final RemoteStore store;
    private final RemoteSecrets secrets;
    private final LongSupplier wall, elapsed;
    private final IntSupplier boot;

    public RemoteKeyholder(Context context, RemoteStore store, RemoteSecrets secrets) {
        this(context, store, secrets, System::currentTimeMillis, SystemClock::elapsedRealtime,
                () -> Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }

    RemoteKeyholder(Context context, RemoteStore store, RemoteSecrets secrets,
            LongSupplier wall, LongSupplier elapsed, IntSupplier boot) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.secrets = secrets;
        this.wall = wall;
        this.elapsed = elapsed;
        this.boot = boot;
    }

    @Override public State state() {
        synchronized (store) {
            try {
                RemoteStore.Peer peer = assignedDom();
                if (peer == null) return State.NONE;
                return secrets.controllerSecret(peer.localRole, peer.identity, peer.generation) == null
                        ? (number(peer, "provisioned", 0) == 0 ? State.PENDING : State.UNAVAILABLE) : State.READY;
            } catch (GeneralSecurityException | RuntimeException unavailable) { return State.UNAVAILABLE; }
        }
    }

    @Override public String assignedDomName() {
        synchronized (store) {
            try { RemoteStore.Peer peer = assignedDom(); return peer == null ? "" : peer.name; }
            catch (RuntimeException unavailable) { return ""; }
        }
    }

    @Override public boolean withLocalAuthority(java.util.function.BooleanSupplier action) {
        synchronized (store) { return state() == State.NONE && action.getAsBoolean(); }
    }

    /** Called only after the handshake has validated the mutually approved identities. */
    public String provisionSub(RemoteStore.Peer peer, String localIdentity) throws GeneralSecurityException {
        synchronized (store) {
            RemoteStore.Peer current = requirePeer(peer, RemoteProtocol.Role.SUB, false);
            String secret = secrets.controllerSecret(current.localRole, localIdentity, current.identity, current.generation);
            if (secret == null) {
                if (number(current, "provisioned", 0) != 0)
                    throw new GeneralSecurityException("Controller credential unavailable");
                secret = Totp.newSecret();
                if(current.state==RemoteStore.State.ACTIVE)ControllerPinManager.lockNow();
            }
            secrets.saveControllerSecret(current.localRole, localIdentity, current.identity, current.generation, secret);
            put(current, "provisioned", 1);
            return secret;
        }
    }

    /** The seed is accepted only for this Dom's already-approved Sub generation. */
    public void receiveDom(RemoteStore.Peer peer, String localIdentity, String secret) throws GeneralSecurityException {
        synchronized (store) {
            RemoteStore.Peer current = requirePeer(peer, RemoteProtocol.Role.DOM, false);
            String previous = secrets.controllerSecret(current.localRole, localIdentity, current.identity, current.generation);
            if (previous == null && number(current, "provisioned", 0) != 0)
                throw new GeneralSecurityException("Controller credential unavailable");
            secrets.saveControllerSecret(current.localRole, localIdentity, current.identity, current.generation, secret);
            put(current, "provisioned", 1);
        }
    }

    public Code code(RemoteStore.Peer peer) throws GeneralSecurityException {
        synchronized (store) {
            State authority;
            try { authority = com.subhub.app.people.People.keyholder().state(); }
            catch (RuntimeException unavailable) { throw new GeneralSecurityException("Keyholder authority unavailable"); }
            if (authority != State.NONE && authority != State.READY)
                throw new GeneralSecurityException("Keyholder authority unavailable");
            if (!ControllerPinManager.isDomModeActive() || !ControllerPinManager.hasCredentials(context))
                throw new SecurityException("Keyholder access required");
            RemoteStore.Peer current = requirePeer(peer, RemoteProtocol.Role.DOM, true);
            String secret = secrets.controllerSecret(current.localRole, current.identity, current.generation);
            if (secret == null) throw new GeneralSecurityException("Controller credential unavailable");
            long now = wall.getAsLong();
            if (now < 0 || now > Long.MAX_VALUE - Totp.STEP_MILLIS)
                throw new GeneralSecurityException("Controller clock unavailable");
            long step = now / Totp.STEP_MILLIS;
            return new Code(Totp.code(secret, step), (step + 1) * Totp.STEP_MILLIS);
        }
    }

    @Override public ControllerAuthenticator.Result verifyCode(String code) {
        synchronized (store) {
            try {
                SQLiteDatabase db = store.getWritableDatabase();
                db.beginTransaction();
                try {
                    RemoteStore.Peer peer = assignedDom();
                    if (peer == null || peer.state != RemoteStore.State.ACTIVE)
                        return ControllerAuthenticator.Result.UNAVAILABLE;
                    String secret = secrets.controllerSecret(peer.localRole, peer.identity, peer.generation);
                    if (secret == null) return ControllerAuthenticator.Result.UNAVAILABLE;
                    if (cooldown(peer) > 0) return ControllerAuthenticator.Result.THROTTLED;
                    long step = Totp.verify(secret, code, wall.getAsLong(), number(peer, "last_step", -1));
                    if (step < 0) {
                        long attempts = Math.min(20, number(peer, "attempts", 0) + 1);
                        put(peer, "attempts", attempts);
                        if (attempts >= 5) {
                            long delay = Math.min(15 * 60_000L, 30_000L << Math.min(5, attempts - 5));
                            put(peer, "blocked_wall", Math.addExact(wall.getAsLong(), delay));
                            put(peer, "blocked_elapsed", Math.addExact(elapsed.getAsLong(), delay));
                            put(peer, "blocked_boot", boot.getAsInt());
                        }
                        db.setTransactionSuccessful();
                        return ControllerAuthenticator.Result.INVALID;
                    }
                    requirePeer(peer, RemoteProtocol.Role.SUB, true);
                    put(peer, "last_step", step);
                    put(peer, "attempts", 0);
                    put(peer, "blocked_wall", 0);
                    put(peer, "blocked_elapsed", 0);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                // Only a durably consumed code can authorize the local recovery session.
                ControllerPinManager.enterDomMode();
                return ControllerAuthenticator.Result.SUCCESS;
            } catch (GeneralSecurityException | RuntimeException unavailable) {
                return ControllerAuthenticator.Result.UNAVAILABLE;
            }
        }
    }

    /** The store must persist REVOKED before invoking this cleanup. */
    public void revoked(String identity, String generation) {
        synchronized (store) {
            RemoteStore.Peer peer = store.peer(identity, generation);
            if (peer == null || peer.state != RemoteStore.State.REVOKED)
                throw new SecurityException("Relationship is not revoked");
            if (peer.localRole == RemoteProtocol.Role.SUB) ControllerPinManager.lockNow();
            secrets.removeControllerSecrets(identity, generation);
        }
    }

    private RemoteStore.Peer assignedDom() {
        RemoteStore.Peer selected = null;
        for (RemoteStore.Peer peer : store.peers(false)) {
            if (peer.localRole != RemoteProtocol.Role.SUB || peer.state != RemoteStore.State.ACTIVE) continue;
            if (selected != null) throw new SecurityException("Ambiguous keyholder authority");
            selected = peer;
        }
        return selected;
    }

    private RemoteStore.Peer requirePeer(RemoteStore.Peer peer, RemoteProtocol.Role role, boolean active) {
        if (peer == null) throw new SecurityException("Relationship unavailable");
        RemoteStore.Peer current = store.peer(peer.identity, peer.generation);
        if (current == null || current.localRole != role || current.state == RemoteStore.State.REVOKED
                || active && current.state != RemoteStore.State.ACTIVE)
            throw new SecurityException("Relationship unavailable");
        return current;
    }

    private long cooldown(RemoteStore.Peer peer) {
        long deadline = number(peer, "blocked_wall", 0);
        if (deadline == 0) return 0;
        int currentBoot = boot.getAsInt();
        long remaining = currentBoot >= 0 && number(peer, "blocked_boot", -2) == currentBoot
                ? number(peer, "blocked_elapsed", 0) - elapsed.getAsLong() : deadline - wall.getAsLong();
        return Math.max(0, Math.min(15 * 60_000L, remaining));
    }

    private long number(RemoteStore.Peer peer, String name, long fallback) {
        return Long.parseLong(store.metadata(peer.identity, peer.generation, PREFIX + name, Long.toString(fallback)));
    }

    private void put(RemoteStore.Peer peer, String name, long value) {
        store.putMetadata(peer.identity, peer.generation, PREFIX + name, Long.toString(value));
    }
}
