package com.subhub.app.remote;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.people.People;
import com.subhub.app.security.ControllerAuthenticator;
import com.subhub.app.security.ControllerPinManager;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.KeyholderProvider;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.settings.SettingsRepository;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Owns local command reviews. Advisory receiver facts and a displayed review never grant native authority. */
final class RemoteSessionControlCoordinator {
    private static final int MAX_REVIEWS = 32;
    private final Context context;
    private final RemoteStore store;
    private final String self;
    private final RemoteSessionReadiness.Source systemSource;
    // All access uses the Store monitor; no registry lock is held across a provider observation.
    private final Map<String, Minted> reviews = new LinkedHashMap<>();

    RemoteSessionControlCoordinator(Context context, RemoteStore store, String self) {
        this(context, store, self, null);
    }
    RemoteSessionControlCoordinator(Context context, RemoteStore store, String self, RemoteSessionReadiness.Source source) {
        this.context = context.getApplicationContext(); this.store = Objects.requireNonNull(store);
        this.self = RemoteProtocol.peerId(self); this.systemSource = source;
    }
    private static final class Minted {
        final RemoteSessionRuntime.Review review;
        final RemoteSessionClock.Bounds lifetime;
        final RemoteSessionControlState.Received source;
        final Authority authority;
        Minted(RemoteSessionRuntime.Review review, RemoteSessionClock.Sample clock, RemoteSessionControlState.Received source, Authority authority) {
            this.review = review; this.lifetime = RemoteSessionClock.Bounds.begin(review.request, clock); this.source = source; this.authority = authority;
        }
    }
    private static final class Authority {
        final KeyholderProvider provider;
        final KeyholderProvider.State state;
        final RemoteStore.Peer governing;
        final RemoteSessionClock.Sample began, completed;
        final String credentials;
        final DeviceRolePreferences.Mode role;
        final boolean localDomWorkspace;
        final long workspaceRevision;
        Authority(KeyholderProvider provider, KeyholderProvider.State state, RemoteStore.Peer governing,
                RemoteSessionClock.Sample began, RemoteSessionClock.Sample completed, String credentials, DeviceRolePreferences.Mode role, boolean localDomWorkspace, long workspaceRevision) {
            this.provider = provider; this.state = state; this.governing = governing; this.began = began; this.completed = completed;
            this.credentials = credentials; this.role = role;
            this.localDomWorkspace=localDomWorkspace;this.workspaceRevision=workspaceRevision;
        }
    }
    private void outsideLocks() {
        if (Thread.holdsLock(store) || Thread.holdsLock(SubFeaturePolicy.LOCK) || Thread.holdsLock(RuntimeSessionIdentity.LOCK))
            throw new IllegalStateException("Observe controller authority outside locks");
    }
    private String credentialWitness() {
        // Compare the saved salted/encrypted records without decrypting under SQLite. This digest stays process-local.
        android.content.SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        android.content.SharedPreferences authenticator = context.getSharedPreferences("subhub_controller_auth", 0);
        org.json.JSONArray values = new org.json.JSONArray();
        values.put(main.getString("controller_pin_salt", "")).put(main.getString("controller_pin_hash", ""))
                .put(authenticator.getString("encrypted_secret", ""));
        return RemoteProtocol.sha256(values.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private Authority observeAuthority() {
        outsideLocks();
        RemoteSessionClock.Sample began = RemoteSessionClock.observe(context);
        long workspaceRevision=ControllerPinManager.domWorkspaceRevision();
        boolean localDomWorkspace=ControllerPinManager.isLocalDomWorkspaceUnlocked();
        KeyholderProvider provider = People.keyholder();
        String credentials = credentialWitness(); DeviceRolePreferences.Mode role = DeviceRolePreferences.get(context);
        RemoteStore.Peer before = RemoteSessionRuntime.assignedDom(store);
        KeyholderProvider.State state = localDomWorkspace ? null : provider.state();
        RemoteStore.Peer after = RemoteSessionRuntime.assignedDom(store);
        if (!samePeer(before, after) || !credentials.equals(credentialWitness())
                || workspaceRevision!=ControllerPinManager.domWorkspaceRevision()
                || localDomWorkspace!=ControllerPinManager.isLocalDomWorkspaceUnlocked()
                || !localDomWorkspace&&state != KeyholderProvider.State.NONE && state != KeyholderProvider.State.READY)
            throw new SecurityException("Controller authority unavailable");
        return new Authority(provider, state, after, began, RemoteSessionClock.observe(context), credentials, role, localDomWorkspace, workspaceRevision);
    }
    private static boolean samePeer(RemoteStore.Peer left, RemoteStore.Peer right) {
        return left == null ? right == null : right != null && left.identity.equals(right.identity)
                && left.generation.equals(right.generation) && left.localRole == right.localRole && left.state == right.state;
    }
    private static boolean recent(RemoteSessionClock.Sample before, RemoteSessionClock.Sample now) {
        if (before.boot < 0 || before.boot != now.boot || before.wall <= 0 || now.wall < before.wall
                || before.elapsed < 0 || now.elapsed < before.elapsed) return false;
        long elapsed = now.elapsed - before.elapsed;
        return elapsed <= 5000 && Math.abs(now.wall - before.wall - elapsed) <= 2000;
    }
    /** Pure local checks only: provider/Keystore observation has already finished outside this transaction. */
    private void requireAuthority(Authority observed, RemoteSessionClock.Sample now) {
        if (!recent(observed.began, observed.completed) || !recent(observed.began, now) || now.elapsed < observed.completed.elapsed
                || People.keyholder() != observed.provider || !ControllerPinManager.isDomWorkspaceUnlocked()
                || observed.workspaceRevision!=ControllerPinManager.domWorkspaceRevision()
                || observed.localDomWorkspace!=ControllerPinManager.isLocalDomWorkspaceUnlocked()
                || !observed.localDomWorkspace&&!ControllerPinManager.isSessionUnlocked()
                || !DeviceRolePreferences.supportsDom(context) || !DeviceRolePreferences.isDomWorkspace(context)
                || observed.role != DeviceRolePreferences.get(context) || !observed.credentials.equals(credentialWitness())
                || !samePeer(observed.governing, RemoteSessionRuntime.assignedDom(store)))
            throw new SecurityException("Controller authority changed");
        if (observed.localDomWorkspace) {
            if(!ControllerPinManager.isConfigured(context))throw new SecurityException("Local Dom credentials required");
            return;
        }
        if (observed.state == KeyholderProvider.State.NONE) {
            if (observed.governing != null || !ControllerPinManager.isConfigured(context) && !new ControllerAuthenticator(context).isPaired())
                throw new SecurityException("Controller credentials required");
        } else if (observed.state != KeyholderProvider.State.READY || observed.governing == null) {
            throw new SecurityException("Controller authority unavailable");
        }
    }
    private RemoteStore.Peer currentDomPeer(RemoteStore.Peer requested) {
        if (requested == null) throw new SecurityException("Current Sub relationship required");
        RemoteStore.Peer current = store.peer(requested.identity, requested.generation);
        if (current == null || current.state != RemoteStore.State.ACTIVE || current.localRole != RemoteProtocol.Role.DOM
                || current.identity.equals(self)) throw new SecurityException("Current Sub relationship required");
        return current;
    }
    private boolean supported(RemoteStore.Peer peer) { return "1".equals(store.metadata(peer.identity, peer.generation, "session_control_version", "0")); }
    private RemoteSessionControlState.Received received(RemoteStore.Peer peer) {
        try {
            String body = store.metadata(peer.identity, peer.generation, RemoteSessionControlState.METADATA, "");
            if (body.isEmpty()) return null;
            RemoteSessionControlState.Received value = RemoteSessionControlState.Received.parse(new JSONObject(body));
            return self.equals(value.state.recipient) && peer.identity.equals(value.state.sender)
                    && peer.generation.equals(value.state.generation) ? value : null;
        } catch (JSONException | IllegalArgumentException invalid) { return null; }
    }
    private RemoteSessionRuntime.Record latest(RemoteStore.Peer peer) throws JSONException {
        // The durable admission floor never moves backward when an old result arrives or a newer row is pruned.
        long floor = Long.parseLong(store.metadata(peer.identity, peer.generation, "session_control_OUTGOING_floor", "0"));
        if (floor < 0) throw new SecurityException("Invalid command sequence floor");
        if (floor == 0) return null;
        String id;
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT id FROM session_control_journal WHERE peer=? AND generation=? AND direction='OUTGOING' AND sequence=?",
                new String[]{peer.identity, peer.generation, Long.toString(floor)})) {
            if (!rows.moveToFirst()) return null; id = rows.getString(0);
        }
        RemoteSessionRuntime.Record record = store.sessionControl(peer.identity, peer.generation, RemoteSessionRuntime.Direction.OUTGOING, id);
        if (record == null || !self.equals(record.request.sender) || !peer.identity.equals(record.request.recipient))
            throw new SecurityException("Latest command scope changed");
        return record;
    }
    private RemoteSessionRuntime.Snapshot snapshot(RemoteStore.Peer peer, RemoteSessionClock.Sample now) throws JSONException {
        RemoteSessionRuntime.Record latest = latest(peer);
        RemoteSessionControlState.Received received = received(peer);
        if (received == null) return new RemoteSessionRuntime.Snapshot(peer.identity, peer.generation, true, false, false,
                0, null, "", "", RemoteSessionRuntime.Readiness.UNAVAILABLE, RemoteSessionCommands.Reason.READINESS_REQUIRED, "", latest);
        RemoteSessionControlState state = received.state;
        boolean fresh = received.fresh(now);
        return new RemoteSessionRuntime.Snapshot(peer.identity, peer.generation, true, fresh && state.canStart, fresh && state.canStop,
                state.appliedRevision, state.service, state.idleFence, state.sessionIdentity,
                fresh ? state.readiness : RemoteSessionRuntime.Readiness.UNAVAILABLE,
                fresh ? state.reason : RemoteSessionCommands.Reason.CLOCK_CHANGED,
                fresh ? requestWitness(state, state.active ? RemoteSessionCommands.Action.STOP : RemoteSessionCommands.Action.START) : "", latest);
    }
    RemoteSessionRuntime.Snapshot snapshot(RemoteStore.Peer requested) throws JSONException {
        Authority authority = observeAuthority();
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                RemoteSessionClock.Sample now = RemoteSessionClock.local(); requireAuthority(authority, now);
                RemoteStore.Peer current = currentDomPeer(requested);
                RemoteSessionRuntime.Snapshot result = supported(current) ? snapshot(current, now) : RemoteSessionRuntime.Snapshot.unsupported(current);
                db.setTransactionSuccessful(); return result;
            } finally { db.endTransaction(); }
        }
    }
    RemoteSessionRuntime.Review review(RemoteStore.Peer requested, RemoteSessionCommands.Action action) throws JSONException {
        Objects.requireNonNull(action); Authority authority = observeAuthority();
        synchronized (store) {
            RemoteSessionClock.Sample now = RemoteSessionClock.local(); requireAuthority(authority, now);
            Iterator<Minted> iterator = reviews.values().iterator();
            while (iterator.hasNext()) {
                Minted item = iterator.next();
                if (item.lifetime.check(item.review.request, now) != RemoteSessionCommands.Reason.NONE || !item.source.fresh(now)) iterator.remove();
            }
            if (reviews.size() >= MAX_REVIEWS) throw new IllegalStateException("Session reviews full");
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            RemoteSessionRuntime.Review review; RemoteSessionControlState.Received received;
            try {
                now = RemoteSessionClock.local();
                requireAuthority(authority, now); RemoteStore.Peer peer = currentDomPeer(requested);
                if (!supported(peer)) throw new UnsupportedOperationException("Session control unavailable");
                RemoteSessionRuntime.Snapshot state = snapshot(peer, now); requireEligible(state, action);
                received = received(peer);
                if (received == null || !received.fresh(now)) throw new SecurityException("Session observation unavailable");
                long sequence = store.allocateSessionSequence(peer.identity, peer.generation), expires = Math.addExact(now.wall, RemoteSessionCommands.LIFETIME_MILLIS);
                RemoteSessionCommands.Request request = action == RemoteSessionCommands.Action.START
                        ? RemoteSessionCommands.Request.start(UUID.randomUUID().toString(), self, peer.identity, peer.generation, sequence,
                                state.appliedRevision, now.wall, expires, state.idleFence, state.service)
                        : RemoteSessionCommands.Request.stop(UUID.randomUUID().toString(), self, peer.identity, peer.generation, sequence,
                                state.appliedRevision, now.wall, expires, state.sessionIdentity);
                review = new RemoteSessionRuntime.Review(request, state.sourceWitness, now.wall); db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            reviews.put(review.request.id, new Minted(review, now, received, authority)); return review;
        }
    }
    String confirm(RemoteSessionRuntime.Review review) throws JSONException {
        Objects.requireNonNull(review); Authority authority = observeAuthority();
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                RemoteSessionClock.Sample now = RemoteSessionClock.local(); requireAuthority(authority, now);
                RemoteSessionCommands.Request request = review.request;
                RemoteStore.Peer peer = store.peer(request.recipient, request.generation); currentDomPeer(peer);
                if (!self.equals(request.sender) || !request.digest().equals(review.requestDigest)) throw new SecurityException("Session review scope changed");
                if (!supported(peer)) throw new UnsupportedOperationException("Session control unavailable");
                RemoteSessionRuntime.Record known = store.sessionControl(peer.identity, peer.generation, RemoteSessionRuntime.Direction.OUTGOING, request.id);
                if (known != null) {
                    if (!known.request.digest().equals(review.requestDigest)) throw new SecurityException("Session review identity reused");
                } else {
                    Minted minted = reviews.get(request.id);
                    if (minted == null || minted.review != review || minted.lifetime.check(request, now) != RemoteSessionCommands.Reason.NONE
                            || !minted.source.fresh(now) || minted.authority.provider != authority.provider
                            || minted.authority.state != authority.state || minted.authority.role != authority.role
                            || minted.authority.workspaceRevision!=authority.workspaceRevision
                            || minted.authority.localDomWorkspace!=authority.localDomWorkspace
                            || !minted.authority.credentials.equals(authority.credentials)
                            || !samePeer(minted.authority.governing, authority.governing))
                        throw new SecurityException("Session review expired or unavailable");
                    RemoteSessionRuntime.Snapshot state = snapshot(peer, now); requireEligible(state, request.action);
                    RemoteSessionControlState.Received current = received(peer);
                    if (current == null || !current.fresh(now) || !requestWitness(current.state, request.action).equals(review.sourceWitness))
                        throw new SecurityException("Session observation changed");
                    store.rememberSessionControl(peer.identity, peer.generation, RemoteSessionRuntime.Direction.OUTGOING, request, now);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            reviews.remove(review.request.id); return review.request.id;
        }
    }
    private static void requireEligible(RemoteSessionRuntime.Snapshot state, RemoteSessionCommands.Action action) {
        if (!state.supported || !(action == RemoteSessionCommands.Action.START ? state.canStart : state.canStop))
            throw new SecurityException("Session action unavailable");
    }
    private static String requestWitness(RemoteSessionControlState state, RemoteSessionCommands.Action action) throws JSONException {
        if (action == RemoteSessionCommands.Action.START) return state.witness();
        // Start providers/configuration are independent of the exact active session a Stop review targets.
        JSONObject value = new JSONObject().put("sender", state.sender).put("recipient", state.recipient)
                .put("generation", state.generation).put("active", state.active).put("sessionIdentity", state.sessionIdentity).put("canStop", state.canStop);
        return RemoteProtocol.sha256(RemoteProtocol.canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Receiver advice uses independent Start providers and a clock-only Stop observation; neither authorizes application. */
    RemoteSessionControlState receiverState(RemoteStore.Peer peer) throws JSONException {
        outsideLocks();
        RemoteSessionReadiness.Observation start = systemSource == null ? RemoteSessionReadiness.observe(context, store)
                : RemoteSessionReadiness.observe(store, systemSource);
        RemoteSessionReadiness.Observation clock = systemSource == null ? RemoteSessionReadiness.observeClock(context, store)
                : RemoteSessionReadiness.observeClock(store, systemSource);
        synchronized (SubFeaturePolicy.LOCK) {
            synchronized (store) {
                synchronized (RuntimeSessionIdentity.LOCK) {
                    RemoteStore.Peer current = store.peer(peer.identity, peer.generation), governing = RemoteSessionRuntime.assignedDom(store);
                    if (current == null || current.localRole != RemoteProtocol.Role.SUB || current.state != RemoteStore.State.ACTIVE
                            || !samePeer(current, governing) || !supported(current) || !DeviceRolePreferences.supportsSub(context))
                        throw new SecurityException("Current Dom relationship required");
                    RemoteSessionClock.Sample now = RemoteSessionClock.local();
                    if (!clock.fresh(now)) throw new IllegalStateException("Local session clock unavailable");
                    RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, now);
                    RemoteSessionCommands.Duration service = null; long revision = 0; boolean revisionKnown = false;
                    try {
                        android.content.SharedPreferences duration = context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS, 0);
                        service = new RemoteSessionCommands.Duration(duration.getLong("duration", -1), duration.getLong("minimum", 3600000),
                                duration.getLong("maximum", 86400000), duration.getBoolean("hidden", false));
                        revision = Long.parseLong(store.metadata(peer.identity, peer.generation, "applied_revision", "0"));
                        revisionKnown = revision >= 0;
                    } catch (RuntimeException unavailable) { /* Invalid Start configuration never disables an independently observed Stop. */ }
                    if (!revisionKnown) revision = 0;
                    RemoteSessionRuntime.Readiness readiness = start.current(context, now);
                    RemoteSessionRuntime.HardcoreConsent consent = null; boolean hardcore = false;
                    try {
                        hardcore = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0).getBoolean(HardcoreModeManager.KEY_REQUESTED, false);
                        if (hardcore && start.fresh(now) && start.adminActive) consent = SubFeaturePolicy.hardcoreStartConsent(context, store, self);
                    } catch (RuntimeException unavailable) { /* Consent/configuration failure cannot manufacture Start advice or conceal an observed Stop target. */ }
                    RemoteSessionCommands.Reason reason;
                    if (!hardcore || !start.fresh(now) || !start.adminActive) reason = RemoteSessionCommands.Reason.HARDCORE_REQUIRED;
                    else if (consent == null || consent.acceptedAt > now.wall || !peer.identity.equals(consent.dom)
                            || !self.equals(consent.recipient) || !peer.generation.equals(consent.generation)) reason = RemoteSessionCommands.Reason.CONSENT_REQUIRED;
                    else if (!revisionKnown || service == null) reason = RemoteSessionCommands.Reason.REVISION_CHANGED;
                    else if (runtime.active || runtime.idleFence.isEmpty()) reason = RemoteSessionCommands.Reason.SESSION_CHANGED;
                    else if (readiness != RemoteSessionRuntime.Readiness.APP_MODE_READY) reason = RemoteSessionCommands.Reason.READINESS_REQUIRED;
                    else reason = RemoteSessionCommands.Reason.NONE;
                    return new RemoteSessionControlState(self, peer.identity, peer.generation, now.wall, revision, service,
                            runtime.active, runtime.active ? "" : runtime.idleFence, runtime.identity, readiness, reason,
                            reason == RemoteSessionCommands.Reason.NONE, runtime.active && !runtime.identity.isEmpty());
                }
            }
        }
    }
}
