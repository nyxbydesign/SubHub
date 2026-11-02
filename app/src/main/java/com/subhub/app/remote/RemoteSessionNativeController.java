package com.subhub.app.remote;

import static com.subhub.app.remote.RemoteSessionCommands.*;
import static com.subhub.app.remote.RemoteSessionRuntime.Direction.INCOMING;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.appmode.SessionExpiryEffects;
import com.subhub.app.security.DeviceRolePreferences;
import com.subhub.app.security.HardcoreModeManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Receiver-local journal application. No wire, UI, or capability advertisement calls this yet. */
final class RemoteSessionNativeController {
    private final Context context;
    private final RemoteStore store;
    private final String self;
    private final RemoteSessionNative nativeSession;
    private final RemoteSessionReadiness.Source systemSource;

    RemoteSessionNativeController(Context context, RemoteStore store, String receiverIdentity) {
        this(context, store, receiverIdentity, null);
    }
    RemoteSessionNativeController(Context context, RemoteStore store, String receiverIdentity,
            RemoteSessionReadiness.Source systemSource) {
        this.context = context.getApplicationContext(); this.store = Objects.requireNonNull(store);
        this.self = RemoteProtocol.peerId(receiverIdentity); this.systemSource = systemSource;
        this.nativeSession = RemoteSessionNative.get(this.context);
    }

    /** Polling retries use the journal identity; they cannot supply new native preparations or authority. */
    RemoteSessionRuntime.Record advance(String peer, String generation, String id) throws JSONException {
        RemoteSessionRuntime.Record recorded = store.sessionControl(peer, generation, INCOMING, id);
        if (recorded == null) throw new SecurityException("Unknown session command");
        boolean stopping = recorded.request.action == Action.STOP;
        RemoteSessionReadiness.Observation observed = systemSource == null
                ? stopping ? RemoteSessionReadiness.observeClock(context, store) : RemoteSessionReadiness.observe(context, store)
                : stopping ? RemoteSessionReadiness.observeClock(store, systemSource) : RemoteSessionReadiness.observe(store, systemSource);
        SQLiteDatabase db = store.getWritableDatabase();
        Effects effects = new Effects();
        RemoteSessionRuntime.Record result;
        try {
            synchronized (SubFeaturePolicy.LOCK) {
                synchronized (store) {
                    synchronized (RuntimeSessionIdentity.LOCK) {
                        // An outer transaction would prevent preparation from becoming durable before the effect.
                        if (db.inTransaction()) throw new IllegalStateException("Native application requires its own transactions");
                        RemoteSessionClock.Sample now = RemoteSessionClock.local();
                        RemoteSessionRuntime.Record current = store.checkSessionControlClock(peer, generation, INCOMING, id, now);
                        if (current.result.state.terminal()) return current;
                        if (stopping && !observed.fresh(now)) return current.result.state == State.APPLYING
                                ? failed(current, Reason.CLOCK_CHANGED, now) : reject(current, Reason.CLOCK_CHANGED, now);
                        if (current.result.state == State.ACKNOWLEDGED) {
                            Reason reason = RemoteSessionRuntime.validateIncoming(current.request, facts(current.request, observed, now), now.wall);
                            if (reason != Reason.NONE) return reject(current, reason, now);
                            RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, now);
                            if (current.request.action == Action.START && !runtime.statsIdentity.isEmpty())
                                return reject(current, Reason.SESSION_CHANGED, now);
                            JSONObject prepared = current.request.action == Action.START
                                    ? RemoteSessionNative.prepare(current.request, now).json()
                                    : nativeSession.prepareStop(current.request, now).json();
                            current = store.prepareNativeSessionControl(peer, generation, id, prepared, now);
                            if (current.result.state.terminal()) return current;
                        }
                        if (current.result.state != State.APPLYING) throw new SecurityException("Incoming application receipt required");
                        // The preparation transaction has ended. A second transaction fences independent-handle revocation.
                        db.beginTransaction();
                        try {
                            current = store.sessionControl(peer, generation, INCOMING, id);
                            if (!current.result.state.terminal()) {
                                JSONObject prepared = store.nativeSessionPreparation(peer, generation, id);
                                now = RemoteSessionClock.local();
                                RemoteSessionRuntime.Facts facts = facts(current.request, observed, now);
                                result = current.request.action == Action.START
                                        ? start(current, RemoteSessionNative.PreparedStart.parse(prepared), facts, observed, now, effects)
                                        : stop(current, RemoteSessionNative.PreparedStop.parse(prepared), facts, observed, now, effects);
                            } else result = current;
                            db.setTransactionSuccessful();
                        } finally { db.endTransaction(); }
                    }
                }
            }
        } finally {
            // Historical capture closure remains safe even if SQLite's later terminal result could not commit.
            if (effects.capture != null) ScreenCaptureService.requestStop(effects.capture);
            if (effects.scope != null) SessionExpiryEffects.post(context, effects.scope, null, false, false);
        }
        return result;
    }

    private RemoteSessionRuntime.Facts facts(Request request, RemoteSessionReadiness.Observation observed,
            RemoteSessionClock.Sample now) {
        RemoteStore.Peer peer = store.peer(request.sender, request.generation);
        RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, now);
        SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        SharedPreferences duration = context.getSharedPreferences(RemoteConfiguration.DURATION_PREFS, 0);
        Duration service = null;
        try {
            service = new Duration(duration.getLong("duration", -1), duration.getLong("minimum", 3600000),
                    duration.getLong("maximum", 86400000), duration.getBoolean("hidden", false));
        } catch (IllegalArgumentException | ClassCastException invalid) { /* Unreadable local settings cannot authorize Start. */ }
        long revision = -1; int version = 0;
        try {
            revision = Long.parseLong(store.metadata(request.sender, request.generation, "applied_revision", "0"));
            version = Integer.parseInt(store.metadata(request.sender, request.generation, "session_control_version", "0"));
        } catch (NumberFormatException invalid) { /* Malformed negotiation is unsupported. */ }
        return new RemoteSessionRuntime.Facts(self, request.sender, request.generation,
                peer == null ? RemoteProtocol.Role.DOM : peer.localRole, peer != null,
                peer != null && peer.state == RemoteStore.State.ACTIVE, DeviceRolePreferences.supportsSub(context),
                version, revision, runtime.active, runtime.idleFence, runtime.identity, service,
                main.getBoolean(HardcoreModeManager.KEY_REQUESTED, false) && observed.fresh(now) && observed.adminActive,
                SubFeaturePolicy.hardcoreStartConsent(context, store, self), observed.current(context, now));
    }

    private RemoteSessionRuntime.Record start(RemoteSessionRuntime.Record record, RemoteSessionNative.PreparedStart prepared,
            RemoteSessionRuntime.Facts facts, RemoteSessionReadiness.Observation observed, RemoteSessionClock.Sample now,
            Effects effects) throws JSONException {
        RemoteSessionNative.Marker marker = nativeSession.marker();
        boolean owns = marker != null && same(prepared.json(), marker.prepared.json());
        if (!owns) {
            if (!RemoteSessionNative.currentProcess(prepared.process)) return reject(record, Reason.PROCESS_RESTARTED, now);
            Reason reason = record.lifetime.check(record.request, now);
            if (reason == Reason.NONE) reason = RemoteSessionRuntime.validateIncoming(record.request, facts, now.wall);
            if (reason != Reason.NONE) return reject(record, reason, now);
            boolean committed = nativeSession.commitPreparedStart(prepared, now);
            startEffects(prepared, effects);
            if (!committed) return record;
        } else {
            // Existing effects may only reconcile bookkeeping; no fresh Start or new duration is permitted here.
            if (marker.ended) return reject(record, Reason.SESSION_CHANGED, now);
            Reason reason = recoveryAuthority(record.request, facts, now);
            if (reason != Reason.NONE) return reject(record, reason, now);
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(context, now);
            if (!current.statsIdentity.isEmpty() && !prepared.statistics.identity.equals(current.statsIdentity)
                    || !prepared.pactIdentity.equals(current.pactIdentity)) return reject(record, Reason.SESSION_CHANGED, now);
            startEffects(prepared, effects);
            if (!RemoteSessionNative.reconcile(context)) return record;
        }
        marker = nativeSession.marker();
        RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, now);
        SharedPreferences statistics = context.getSharedPreferences(StatsRepository.PREFS_NAME, 0);
        SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        if (marker == null || !same(prepared.json(), marker.prepared.json()) || marker.ended || !marker.statisticsReady
                || !runtime.active || !runtime.armed || runtime.screenCapture || runtime.paused
                || !prepared.statistics.identity.equals(runtime.statsIdentity)
                || statistics.getLong("active_session_start_ms", 0) != prepared.statistics.startedAt
                || !prepared.pactIdentity.equals(runtime.pactIdentity)
                || prepared.selectedDuration != -1 && (!runtime.pact
                        || main.getLong("commitment_duration", 0) != prepared.selectedDuration
                        || main.getLong("commitment_ends_at", 0) != prepared.endsAt
                        || main.getLong("commitment_ends_elapsed", 0) != prepared.endsElapsed
                        || main.getInt("commitment_boot", -1) != prepared.boot))
            return reject(record, Reason.SESSION_CHANGED, now);
        if (!observed.fresh(RemoteSessionClock.local())
                || observed.current(context, RemoteSessionClock.local()) != RemoteSessionRuntime.Readiness.APP_MODE_READY) return record;
        return applied(record, new Proof(true, runtime.identity, prepared.statistics.startedAt, prepared.endsAt,
                prepared.selectedDuration), now);
    }

    private void startEffects(RemoteSessionNative.PreparedStart prepared, Effects effects) throws JSONException {
        RemoteSessionNative.Marker marker = nativeSession.marker();
        if (marker == null || marker.ended || !same(prepared.json(), marker.prepared.json())) return;
        SharedPreferences main = context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
        if (!main.getBoolean(com.subhub.app.appmode.AppModeManager.KEY_ARMED, false)
                || !prepared.pactIdentity.equals(main.getString("commitment_session_identity", ""))) return;
        if (!prepared.pactIdentity.isEmpty()) {
            RemoteSessionClock.Sample now = RemoteSessionClock.local();
            long trigger = now.boot == prepared.boot ? prepared.endsElapsed
                    : now.elapsed + Math.max(0, prepared.endsAt - now.wall);
            SessionExpiryAlarms.update(context, SessionExpiryAlarms.Kind.PACT, "", prepared.pactIdentity, trigger);
        }
        effects.scope = RuntimeSessionIdentity.componentObservation(context);
    }

    private RemoteSessionRuntime.Record stop(RemoteSessionRuntime.Record record, RemoteSessionNative.PreparedStop prepared,
            RemoteSessionRuntime.Facts facts, RemoteSessionReadiness.Observation observed, RemoteSessionClock.Sample now,
            Effects effects) throws JSONException {
        RemoteSessionNative.StopMarker marker = nativeSession.stopMarker();
        boolean owns = marker != null && same(prepared.json(), marker.prepared.json());
        Reason authority = commonAuthority(record.request, facts);
        if (authority != Reason.NONE) return reject(record, authority, now);
        if (!observed.fresh(now)) return failed(record, Reason.CLOCK_CHANGED, now);
        if (!owns) {
            if (!RemoteSessionNative.currentProcess(prepared.process)) return reject(record, Reason.PROCESS_RESTARTED, now);
            Reason reason = record.lifetime.check(record.request, now);
            if (reason == Reason.NONE) reason = RemoteSessionRuntime.validateIncoming(record.request, facts, now.wall);
            if (reason != Reason.NONE) return reject(record, reason, now);
            boolean committed = nativeSession.commitPreparedStop(prepared, now);
            effects.capture = nativeSession.stopCapture(prepared);
            if (!committed) return record;
        } else {
            effects.capture = nativeSession.stopCapture(prepared);
            if (!prepared.capture.isEmpty() && (effects.capture == null
                    || !effects.capture.isClosed() && record.lifetime.check(record.request, now) != Reason.NONE))
                return failed(record, Reason.APPLICATION_FAILED, now);
            if (!RemoteSessionNative.reconcile(context)) return record;
        }
        marker = nativeSession.stopMarker();
        // A process change destroys a volatile closure reference; it cannot acknowledge historical resources.
        if (!prepared.capture.isEmpty() && effects.capture == null)
            return failed(record, Reason.APPLICATION_FAILED, now);
        if (!prepared.capture.isEmpty() && !effects.capture.isClosed()
                && record.lifetime.check(record.request, now) != Reason.NONE)
            return failed(record, Reason.APPLICATION_FAILED, now);
        RuntimeSessionIdentity.Observed runtime = RuntimeSessionIdentity.observe(context, now);
        if (runtime.active) {
            // A closing original capture can remain active; a later native session cannot be stopped by this request.
            if (runtime.armed || runtime.pact || runtime.paused || !runtime.statsIdentity.isEmpty())
                return reject(record, Reason.SESSION_CHANGED, now);
            return record;
        }
        if (marker == null || !same(prepared.json(), marker.prepared.json()) || !marker.ready
                || !runtime.statsIdentity.isEmpty() || !runtime.pactIdentity.isEmpty() || !runtime.pauseIdentity.isEmpty()) return record;
        if (!prepared.capture.isEmpty() && !effects.capture.isClosed()) return record;
        if (!observed.fresh(RemoteSessionClock.local())) return record;
        effects.scope = RuntimeSessionIdentity.componentObservation(context);
        return applied(record, new Proof(false, "", 0, 0, 0), now);
    }

    private static Reason commonAuthority(Request request, RemoteSessionRuntime.Facts facts) {
        if (!facts.authenticated || !facts.activeRelationship || !facts.supportsSub || facts.localRole != RemoteProtocol.Role.SUB
                || !facts.peer.equals(request.sender) || !facts.generation.equals(request.generation)) return Reason.AUTHORITY_CHANGED;
        if (!facts.self.equals(request.recipient)) return Reason.WRONG_RECIPIENT;
        return facts.negotiatedVersion == VERSION ? Reason.NONE : Reason.UNSUPPORTED;
    }
    private static Reason recoveryAuthority(Request request, RemoteSessionRuntime.Facts facts, RemoteSessionClock.Sample now) throws JSONException {
        Reason reason = commonAuthority(request, facts); if (reason != Reason.NONE) return reason;
        if (!facts.hardcoreEnabled) return Reason.HARDCORE_REQUIRED;
        if (facts.consent == null || facts.consent.acceptedAt > now.wall || !facts.consent.matches(request)) return Reason.CONSENT_REQUIRED;
        if (facts.appliedRevision != request.appliedRevision || facts.service == null
                || !same(facts.service.json(), request.service.json())) return Reason.REVISION_CHANGED;
        return Reason.NONE;
    }
    private static boolean same(JSONObject left, JSONObject right) throws JSONException {
        return RemoteProtocol.canonical(left).equals(RemoteProtocol.canonical(right));
    }
    private RemoteSessionRuntime.Record applied(RemoteSessionRuntime.Record record, Proof proof, RemoteSessionClock.Sample now)
            throws JSONException {
        return save(record, new Result(record.request, State.APPLIED, Reason.NONE, now.wall, proof));
    }
    private RemoteSessionRuntime.Record reject(RemoteSessionRuntime.Record record, Reason reason, RemoteSessionClock.Sample now)
            throws JSONException {
        State state = reason == Reason.EXPIRED ? State.EXPIRED : reason == Reason.READINESS_REQUIRED ? State.NEEDS_SUB_ACTION : State.REJECTED;
        return save(record, new Result(record.request, state, reason, now.wall, null));
    }
    private RemoteSessionRuntime.Record failed(RemoteSessionRuntime.Record record, Reason reason, RemoteSessionClock.Sample now)
            throws JSONException {
        return save(record, new Result(record.request, State.FAILED, reason, now.wall, null));
    }
    private RemoteSessionRuntime.Record save(RemoteSessionRuntime.Record record, Result result) throws JSONException {
        return store.advanceSessionControl(record.request.sender, record.request.generation, INCOMING, record.request.id, result.json());
    }
    private static final class Effects {
        ScreenCaptureService.StopTarget capture;
        RuntimeSessionIdentity.ComponentObservation scope;
    }
}
