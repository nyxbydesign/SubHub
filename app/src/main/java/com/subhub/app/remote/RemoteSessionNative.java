package com.subhub.app.remote;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import com.subhub.app.BuildConfig;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.commitment.CommitmentManager;
import com.subhub.app.commitment.PactDuration;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.json.JSONException;
import org.json.JSONObject;

/** Native application markers are app-owned data; none of these methods decode peer authority. */
public final class RemoteSessionNative implements AutoCloseable {
    public static final String MAIN_KEY = "remote_session_native_v1";
    static final String STOP_MAIN_KEY = "remote_session_native_stop_v1";
    private static final String PROCESS = UUID.randomUUID().toString();
    static boolean currentProcess(String process) { return PROCESS.equals(process); }
    private static final ConcurrentHashMap<Context, RemoteSessionNative> scopes = new ConcurrentHashMap<>();
    private final Context context;
    private final SharedPreferences main;
    private final Supplier<StatsRepository> statsFactory;
    private final Application fixtureApplication;
    private StatsRepository statistics;
    private com.subhub.app.service.ScreenCaptureService.StopTarget stopCapture;

    private RemoteSessionNative(Context context, Supplier<StatsRepository> statsFactory) {
        this(context, statsFactory, null);
    }
    private RemoteSessionNative(Context context, Supplier<StatsRepository> statsFactory,
            Application fixtureApplication) {
        this.context = context.getApplicationContext(); this.statsFactory = Objects.requireNonNull(statsFactory);
        this.fixtureApplication = fixtureApplication;
        main = this.context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0);
    }
    public static RemoteSessionNative get(Context context) {
        Context app = context.getApplicationContext();
        return scopes.computeIfAbsent(app, key -> new RemoteSessionNative(key, () -> new StatsRepository(key)));
    }
    static RemoteSessionNative installFixture(Context context, Supplier<StatsRepository> factory) {
        return registerFixture(new RemoteSessionNative(context, factory));
    }
    static RemoteSessionNative installLegacyFixture(Context scope, Supplier<StatsRepository> factory,
            Application physical) {
        if (!BuildConfig.DEBUG || scope instanceof Application || !(scope instanceof ContextWrapper)
                || scope.getApplicationContext() != scope
                || ((ContextWrapper) scope).getBaseContext().getApplicationContext() != physical)
            throw new SecurityException("Legacy fixture requires an explicit isolated app scope");
        return registerFixture(new RemoteSessionNative(scope, factory, Objects.requireNonNull(physical)));
    }
    private static RemoteSessionNative registerFixture(RemoteSessionNative value) {
        if (scopes.putIfAbsent(value.context, value) != null) throw new IllegalStateException("Native scope already installed");
        return value;
    }
    /** The exact registered instrumentation scope may count legacy work, never obtain the gateway. */
    public static Application legacyFixtureApplication(Context scope) {
        if (!BuildConfig.DEBUG || scope == null) return null;
        RemoteSessionNative exact = scopes.get(scope);
        return exact != null && exact.context == scope ? exact.fixtureApplication : null;
    }
    private StatsRepository stats() { if (statistics == null) statistics = statsFactory.get(); return statistics; }
    public static StatsRepository statistics(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) { return get(context).stats(); }
    }
    @Override public void close() { scopes.remove(context, this); }

    public static final class PreparedStart {
        public final RemoteSessionCommands.Request request;
        public final StatsRepository.NativeSession statistics;
        public final String requestDigest, pactIdentity, process;
        public final long selectedDuration, startedElapsed, endsAt, endsElapsed;
        public final int boot;
        private PreparedStart(RemoteSessionCommands.Request request, String statistics, String pact, long selected,
                RemoteSessionClock.Sample clock, String zone, String process) throws JSONException {
            if (request.action != RemoteSessionCommands.Action.START || !request.service.permits(selected)
                    || clock.wall <= 0 || clock.elapsed < 0 || clock.boot < 0)
                throw new IllegalArgumentException("Invalid prepared native start");
            uuid(process); if (selected != -1) uuid(pact); else if (!pact.isEmpty()) throw new IllegalArgumentException("Unexpected pact");
            this.request = request; this.requestDigest = request.digest(); this.pactIdentity = pact; this.process = process;
            this.statistics = new StatsRepository.NativeSession(statistics, clock.wall, zone);
            this.selectedDuration = selected; this.startedElapsed = clock.elapsed; this.boot = clock.boot;
            this.endsAt = selected == -1 ? 0 : Math.addExact(clock.wall, selected);
            this.endsElapsed = selected == -1 ? 0 : Math.addExact(clock.elapsed, selected);
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("schema", 1).put("request", request.json()).put("requestDigest", requestDigest)
                    .put("statistics", statistics.identity).put("pact", pactIdentity).put("selected", selectedDuration)
                    .put("startedAt", statistics.startedAt).put("elapsed", startedElapsed).put("boot", boot)
                    .put("zone", statistics.zone).put("process", process);
        }
        static PreparedStart parse(JSONObject value) throws JSONException {
            RemoteSessionCommands.keys(value, "schema", "request", "requestDigest", "statistics", "pact", "selected",
                    "startedAt", "elapsed", "boot", "zone", "process");
            if (RemoteProtocol.integer(value, "schema") != 1) throw new JSONException("Unsupported native preparation");
            PreparedStart prepared = new PreparedStart(RemoteSessionCommands.Request.parse(value.getJSONObject("request")),
                    RemoteSessionCommands.text(value, "statistics"), RemoteSessionCommands.text(value, "pact"),
                    RemoteProtocol.integer(value, "selected"), new RemoteSessionClock.Sample(RemoteProtocol.integer(value, "startedAt"),
                            RemoteProtocol.integer(value, "elapsed"), boot(value, "boot")),
                    RemoteSessionCommands.text(value, "zone"), RemoteSessionCommands.text(value, "process"));
            if (!prepared.requestDigest.equals(RemoteSessionCommands.text(value, "requestDigest")))
                throw new SecurityException("Native preparation digest changed");
            return prepared;
        }
    }

    static PreparedStart prepare(RemoteSessionCommands.Request request, RemoteSessionClock.Sample now) throws JSONException {
        if (request.action != RemoteSessionCommands.Action.START) throw new IllegalArgumentException("Start preparation required");
        long chosen = request.service.duration == -2
                ? PactDuration.choose(request.service.minimum, request.service.maximum, new java.security.SecureRandom())
                : request.service.duration;
        return new PreparedStart(request, UUID.randomUUID().toString(), chosen == -1 ? "" : UUID.randomUUID().toString(),
                chosen, now, ZoneId.systemDefault().getId(), PROCESS);
    }

    static final class PreparedStop {
        final RemoteSessionCommands.Request request;
        final String requestDigest, statistics, pact, pause, capture, process;
        final RemoteSessionClock.Sample clock;
        private PreparedStop(RemoteSessionCommands.Request request, String statistics, String pact, String pause,
                String capture, RemoteSessionClock.Sample clock, String process) throws JSONException {
            if (request.action != RemoteSessionCommands.Action.STOP || clock.wall <= 0 || clock.elapsed < 0 || clock.boot < 0)
                throw new IllegalArgumentException("Invalid prepared native stop");
            for (String identity : new String[]{statistics, pact, pause, capture}) if (!identity.isEmpty()) uuid(identity);
            uuid(process); this.request = request; requestDigest = request.digest(); this.statistics = statistics;
            this.pact = pact; this.pause = pause; this.capture = capture; this.clock = clock; this.process = process;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("schema", 1).put("request", request.json()).put("requestDigest", requestDigest)
                    .put("statistics", statistics).put("pact", pact).put("pause", pause).put("capture", capture)
                    .put("wall", clock.wall).put("elapsed", clock.elapsed).put("boot", clock.boot).put("process", process);
        }
        static PreparedStop parse(JSONObject value) throws JSONException {
            RemoteSessionCommands.keys(value, "schema", "request", "requestDigest", "statistics", "pact", "pause", "capture",
                    "wall", "elapsed", "boot", "process");
            if (RemoteProtocol.integer(value, "schema") != 1) throw new JSONException("Unsupported native stop preparation");
            PreparedStop result = new PreparedStop(RemoteSessionCommands.Request.parse(value.getJSONObject("request")),
                    RemoteSessionCommands.text(value, "statistics"), RemoteSessionCommands.text(value, "pact"),
                    RemoteSessionCommands.text(value, "pause"), RemoteSessionCommands.text(value, "capture"),
                    new RemoteSessionClock.Sample(RemoteProtocol.integer(value, "wall"), RemoteProtocol.integer(value, "elapsed"),
                            boot(value, "boot")), RemoteSessionCommands.text(value, "process"));
            if (!result.requestDigest.equals(RemoteSessionCommands.text(value, "requestDigest")))
                throw new SecurityException("Native stop preparation digest changed");
            return result;
        }
    }

    PreparedStop prepareStop(RemoteSessionCommands.Request request, RemoteSessionClock.Sample clock) throws JSONException {
        synchronized (RuntimeSessionIdentity.LOCK) {
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(context, clock);
            if (!current.active || !request.sessionIdentity.equals(current.identity))
                throw new IllegalStateException("Session target changed");
            com.subhub.app.service.ScreenCaptureService.StopTarget capture =
                    com.subhub.app.service.ScreenCaptureService.observedStopTarget(context);
            if (current.screenCapture && capture == null) throw new IllegalStateException("Capture target unavailable");
            return new PreparedStop(request, current.statsIdentity, current.pactIdentity, current.pauseIdentity,
                    capture == null ? "" : capture.identity(), clock, PROCESS);
        }
    }

    static final class StopEnd {
        final RemoteSessionClock.Sample clock;
        final long statisticsStartedAt, durationSeconds;
        final boolean nativeOwned;
        StopEnd(PreparedStop prepared, RemoteSessionClock.Sample clock, long startedAt, long duration, boolean nativeOwned) {
            if (clock.wall <= 0 || clock.elapsed < 0 || clock.boot < 0 || startedAt < 0 || duration < 0
                    || RemoteSessionClock.Bounds.begin(prepared.request, prepared.clock).check(prepared.request, clock)
                            != RemoteSessionCommands.Reason.NONE)
                throw new IllegalArgumentException("Invalid Stop transition clock");
            if (prepared.statistics.isEmpty() ? startedAt != 0 || duration != 0 || nativeOwned
                    : startedAt <= 0 || !nativeOwned && duration != (clock.wall >= startedAt ? (clock.wall - startedAt) / 1000 : 0))
                throw new IllegalArgumentException("Invalid Stop accounting witness");
            this.clock = clock; statisticsStartedAt = startedAt; durationSeconds = duration; this.nativeOwned = nativeOwned;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("wall", clock.wall).put("elapsed", clock.elapsed).put("boot", clock.boot)
                    .put("statisticsStartedAt", statisticsStartedAt).put("durationSeconds", durationSeconds).put("nativeOwned", nativeOwned);
        }
        static StopEnd parse(PreparedStop prepared, JSONObject value) throws JSONException {
            RemoteSessionCommands.keys(value, "wall", "elapsed", "boot", "statisticsStartedAt", "durationSeconds", "nativeOwned");
            if (!(value.get("nativeOwned") instanceof Boolean)) throw new JSONException("Invalid Stop accounting basis");
            return new StopEnd(prepared, new RemoteSessionClock.Sample(RemoteProtocol.integer(value, "wall"),
                    RemoteProtocol.integer(value, "elapsed"), boot(value, "boot")), RemoteProtocol.integer(value, "statisticsStartedAt"),
                    RemoteProtocol.integer(value, "durationSeconds"), value.getBoolean("nativeOwned"));
        }
    }

    static final class StopMarker {
        final PreparedStop prepared;
        final boolean ready;
        final StopEnd end;
        /** Legacy marker construction remains available to readers and compatibility fixtures. */
        StopMarker(PreparedStop prepared, boolean ready) { this(prepared, ready, null); }
        StopMarker(PreparedStop prepared, boolean ready, StopEnd end) { this.prepared = prepared; this.ready = ready; this.end = end; }
        JSONObject json() throws JSONException {
            JSONObject value = new JSONObject().put("schema", end == null ? 1 : 2).put("prepared", prepared.json()).put("ready", ready);
            if (end != null) value.put("end", end.json()); return value;
        }
        static StopMarker parse(JSONObject value) throws JSONException {
            long schema = RemoteProtocol.integer(value, "schema");
            RemoteSessionCommands.keys(value, schema == 2 ? new String[]{"schema", "prepared", "ready", "end"}
                    : new String[]{"schema", "prepared", "ready"});
            if (schema != 1 && schema != 2 || !(value.get("ready") instanceof Boolean))
                throw new JSONException("Invalid native stop marker");
            PreparedStop prepared = PreparedStop.parse(value.getJSONObject("prepared"));
            return new StopMarker(prepared, value.getBoolean("ready"), schema == 2 ? StopEnd.parse(prepared, value.getJSONObject("end")) : null);
        }
    }
    StopMarker stopMarker() throws JSONException {
        String encoded = main.getString(STOP_MAIN_KEY, "");
        if (encoded.isEmpty()) return null;
        if (encoded.length() > 8192) throw new SecurityException("Native stop marker too large");
        return StopMarker.parse(new JSONObject(encoded));
    }

    /** Caller persists the exact preparation and revalidates authority before this native-only commit. */
    boolean commitPreparedStop(PreparedStop prepared, RemoteSessionClock.Sample now) throws JSONException {
        synchronized (RuntimeSessionIdentity.LOCK) {
            StopMarker existing = stopMarker();
            if (existing != null && existing.prepared.request.id.equals(prepared.request.id)
                    && existing.prepared.request.sender.equals(prepared.request.sender)
                    && existing.prepared.request.generation.equals(prepared.request.generation)) {
                if (!RemoteProtocol.canonical(existing.prepared.json()).equals(RemoteProtocol.canonical(prepared.json())))
                    throw new SecurityException("Prepared stop identity reused");
                return reconcileLocked();
            }
            if (!PROCESS.equals(prepared.process) || !reconcileLocked()) return false;
            long elapsed = android.os.SystemClock.elapsedRealtime();
            if (elapsed < now.elapsed || elapsed - now.elapsed > 5000) return false;
            RemoteSessionClock.Sample currentClock = new RemoteSessionClock.Sample(System.currentTimeMillis(), elapsed, now.boot);
            if (RemoteSessionClock.Bounds.begin(prepared.request, prepared.clock)
                    .check(prepared.request, currentClock) != RemoteSessionCommands.Reason.NONE) return false;
            RuntimeSessionIdentity.Observed current = RuntimeSessionIdentity.observe(context, currentClock);
            if (!current.active || !prepared.request.sessionIdentity.equals(current.identity)
                    || !prepared.statistics.equals(current.statsIdentity) || !prepared.pact.equals(current.pactIdentity)
                    || !prepared.pause.equals(current.pauseIdentity)) return false;
            com.subhub.app.service.ScreenCaptureService.StopTarget capture =
                    com.subhub.app.service.ScreenCaptureService.observedStopTarget(context);
            if (!prepared.capture.equals(capture == null ? "" : capture.identity())) return false;
            long startedAt = prepared.statistics.isEmpty() ? 0 : stats().activeSessionStartedAt(prepared.statistics);
            if (!prepared.statistics.isEmpty() && startedAt <= 0) return false;
            Marker start = marker();
            boolean nativeOwned = start != null && !start.ended && !prepared.statistics.isEmpty()
                    && prepared.statistics.equals(start.prepared.statistics.identity)
                    && prepared.statistics.equals(main.getString(StatsRepository.NATIVE_OWNER_KEY, ""))
                    && (start.prepared.pactIdentity.isEmpty() || start.prepared.pactIdentity.equals(current.pactIdentity));
            if (nativeOwned && startedAt != start.prepared.statistics.startedAt) return false;
            // This is the transition snapshot, after potentially blocking statistics reads, not preparation time.
            long transitionElapsed = android.os.SystemClock.elapsedRealtime();
            if (transitionElapsed < now.elapsed || transitionElapsed - now.elapsed > 5000) return false;
            RemoteSessionClock.Sample transition = new RemoteSessionClock.Sample(System.currentTimeMillis(), transitionElapsed, now.boot);
            if (RemoteSessionClock.Bounds.begin(prepared.request, prepared.clock).check(prepared.request, transition)
                    != RemoteSessionCommands.Reason.NONE) return false;
            long duration = nativeOwned ? nativeDuration(start.prepared, transition)
                    : startedAt > 0 && transition.wall >= startedAt ? (transition.wall - startedAt) / 1000 : 0;
            StopEnd end = new StopEnd(prepared, transition, startedAt, duration, nativeOwned);
            stopCapture = capture;
            SharedPreferences.Editor edit = main.edit().putBoolean(AppModeManager.KEY_ARMED, false)
                    .putBoolean("media_projection_session_desired", false)
                    .putString(STOP_MAIN_KEY, RemoteProtocol.canonical(new StopMarker(prepared, false, end).json()));
            if (nativeOwned) edit.putString(MAIN_KEY, RemoteProtocol.canonical(new Marker(start.prepared, true, false, transition, duration).json()));
            CommitmentManager.appendNativeStop(context, edit);
            boolean saved = edit.commit(); RuntimeSessionIdentity.changed();
            if (!saved) return false;
            com.subhub.app.appmode.SessionExpiryAlarms.update(context, com.subhub.app.appmode.SessionExpiryAlarms.Kind.PACT,
                    prepared.pact, "", 0L);
            return reconcileLocked();
        }
    }

    com.subhub.app.service.ScreenCaptureService.StopTarget stopCapture(PreparedStop prepared) throws JSONException {
        synchronized (RuntimeSessionIdentity.LOCK) {
            StopMarker marker = stopMarker();
            return marker != null && marker.prepared.requestDigest.equals(prepared.requestDigest) && stopCapture != null
                    && prepared.capture.equals(stopCapture.identity()) ? stopCapture : null;
        }
    }

    private boolean reconcileStopLocked() throws JSONException {
        StopMarker marker = stopMarker(); if (marker == null) return true;
        PreparedStop prepared = marker.prepared;
        if (marker.ready) return true;
        if (main.getBoolean(AppModeManager.KEY_ARMED, false) || main.getBoolean("media_projection_session_desired", false)
                || !main.getString("commitment_session_identity", "").isEmpty() || !flushMain()) return false;
        com.subhub.app.appmode.SessionExpiryAlarms.update(context, com.subhub.app.appmode.SessionExpiryAlarms.Kind.PACT,
                prepared.pact, "", 0L);
        String activeStatistics = stats().activeSessionIdentity();
        if (!activeStatistics.isEmpty() && !prepared.statistics.equals(activeStatistics)) return false;
        if (!new com.subhub.app.penance.PaidPauseManager(context).finishNativeStop(prepared.pause)) return false;
        if (!finishStoppedStatistics(marker)) return false;
        // All required commits already succeeded; this bit is only a recomputable reconciliation cache.
        main.edit().putString(STOP_MAIN_KEY, RemoteProtocol.canonical(new StopMarker(prepared, true, marker.end).json())).commit();
        return true;
    }

    private boolean finishStoppedStatistics(StopMarker marker) throws JSONException {
        PreparedStop prepared = marker.prepared;
        if (prepared.statistics.isEmpty()) return stats().acknowledgeInactiveSession();
        StopEnd end = marker.end;
        Marker start = marker();
        // Start-first recovery can already have closed statistics; it cannot waive this durable witness comparison.
        if (end != null && end.nativeOwned && (start == null || !start.ended || !prepared.statistics.equals(start.prepared.statistics.identity)
                || start.prepared.statistics.startedAt != end.statisticsStartedAt || start.durationSeconds != end.durationSeconds
                || start.end.wall != end.clock.wall || start.end.elapsed != end.clock.elapsed || start.end.boot != end.clock.boot)) return false;
        if (stats().acknowledgeEndedSession(prepared.statistics)) return true;
        if (end == null) {
            // Schema1 LOCAL markers have no historical end clock; never charge their recovery delay.
            if (start == null || !start.ended || start.end.wall <= 0 || !prepared.statistics.equals(start.prepared.statistics.identity)
                    || stats().activeSessionStartedAt(prepared.statistics) != start.prepared.statistics.startedAt) return false;
            return stats().endSessionForIdentity(prepared.statistics, start.end.wall, start.durationSeconds);
        }
        if (stats().activeSessionStartedAt(prepared.statistics) != end.statisticsStartedAt) return false;
        return stats().endSessionForIdentity(prepared.statistics, end.clock.wall, end.durationSeconds);
    }

    private static long nativeDuration(PreparedStart prepared, RemoteSessionClock.Sample end) {
        return end.boot >= 0 && end.boot == prepared.boot && end.elapsed >= prepared.startedElapsed
                ? (end.elapsed - prepared.startedElapsed) / 1000
                : end.wall >= prepared.statistics.startedAt ? (end.wall - prepared.statistics.startedAt) / 1000 : 0;
    }

    /** Pending or unknown native Stop state cannot supply an ordinary pause's automatic rearm. */
    public static boolean requiresNonRearmingPauseCleanup(Context context, String identity) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            try {
                StopMarker marker = get(context).stopMarker();
                return marker != null && !marker.ready && !identity.isEmpty() && identity.equals(marker.prepared.pause);
            } catch (JSONException | RuntimeException invalid) {
                // Unknown native state must never turn an old pause's rearm flag into a fresh Start.
                return true;
            }
        }
    }

    static final class Marker {
        final PreparedStart prepared;
        final boolean ended, statisticsReady;
        final RemoteSessionClock.Sample end;
        final long durationSeconds;
        Marker(PreparedStart prepared, boolean ended, boolean ready, RemoteSessionClock.Sample end, long durationSeconds) {
            this.prepared = prepared; this.ended = ended; this.statisticsReady = ready; this.end = end; this.durationSeconds = durationSeconds;
        }
        JSONObject json() throws JSONException {
            JSONObject value = new JSONObject().put("schema", 1).put("prepared", prepared.json())
                    .put("ended", ended).put("statisticsReady", statisticsReady);
            if (ended) value.put("end", new JSONObject().put("wall", end.wall).put("elapsed", end.elapsed)
                    .put("boot", end.boot).put("durationSeconds", durationSeconds));
            return value;
        }
        static Marker parse(JSONObject value) throws JSONException {
            if (!(value.get("ended") instanceof Boolean) || !(value.get("statisticsReady") instanceof Boolean))
                throw new JSONException("Invalid native marker flags");
            boolean ended = value.getBoolean("ended");
            RemoteSessionCommands.keys(value, ended ? new String[]{"schema", "prepared", "ended", "statisticsReady", "end"}
                    : new String[]{"schema", "prepared", "ended", "statisticsReady"});
            if (RemoteProtocol.integer(value, "schema") != 1) throw new JSONException("Unsupported native marker");
            RemoteSessionClock.Sample end = null; long duration = 0;
            if (ended) {
                JSONObject clock = value.getJSONObject("end"); RemoteSessionCommands.keys(clock, "wall", "elapsed", "boot", "durationSeconds");
                duration = RemoteProtocol.integer(clock, "durationSeconds");
                if (duration < 0 || RemoteProtocol.integer(clock, "elapsed") < 0) throw new JSONException("Invalid closure clock");
                end = new RemoteSessionClock.Sample(RemoteProtocol.integer(clock, "wall"), RemoteProtocol.integer(clock, "elapsed"), boot(clock, "boot"));
            }
            return new Marker(PreparedStart.parse(value.getJSONObject("prepared")), ended, value.getBoolean("statisticsReady"), end, duration);
        }
    }

    Marker marker() throws JSONException {
        String value = main.getString(MAIN_KEY, "");
        if (value.isEmpty()) return null;
        if (value.length() > 8192) throw new SecurityException("Native marker too large");
        return Marker.parse(new JSONObject(value));
    }
    private boolean flushMain() { return main.edit().putString("remote_native_main_fence", UUID.randomUUID().toString()).commit(); }

    /** Caller has already persisted this preparation and validated peer, consent, configuration and readiness. */
    boolean commitPreparedStart(PreparedStart prepared, RemoteSessionClock.Sample now) throws JSONException {
        synchronized (RuntimeSessionIdentity.LOCK) {
            Marker existing = marker();
            if (existing != null && existing.prepared.request.id.equals(prepared.request.id)
                    && existing.prepared.request.sender.equals(prepared.request.sender)
                    && existing.prepared.request.generation.equals(prepared.request.generation)) {
                if (!RemoteProtocol.canonical(existing.prepared.json()).equals(RemoteProtocol.canonical(prepared.json())))
                    throw new SecurityException("Prepared native identity reused");
                return reconcileLocked() && !existing.ended;
            }
            if (!PROCESS.equals(prepared.process) || !reconcileLocked()) return false;
            long elapsed = android.os.SystemClock.elapsedRealtime();
            if (elapsed < now.elapsed || elapsed - now.elapsed > 5000) return false;
            RemoteSessionClock.Sample current = new RemoteSessionClock.Sample(System.currentTimeMillis(), elapsed, now.boot);
            if (RemoteSessionClock.Bounds.begin(prepared.request,
                    new RemoteSessionClock.Sample(prepared.statistics.startedAt, prepared.startedElapsed, prepared.boot))
                    .check(prepared.request, current) != RemoteSessionCommands.Reason.NONE) return false;
            RuntimeSessionIdentity.Observed observed = RuntimeSessionIdentity.observe(context, current);
            if (observed.active || !observed.statsIdentity.isEmpty() || !prepared.request.idleFence.equals(observed.idleFence)) return false;
            SharedPreferences.Editor edit = main.edit().putBoolean(AppModeManager.KEY_ARMED, true)
                    .putString(StatsRepository.NATIVE_OWNER_KEY, prepared.statistics.identity)
                    .putString(MAIN_KEY, RemoteProtocol.canonical(new Marker(prepared, false, false, null, 0).json()));
            CommitmentManager.appendNativeStart(edit, prepared);
            boolean saved = edit.commit(); RuntimeSessionIdentity.changed();
            return saved && reconcileLocked();
        }
    }

    private boolean reconcileLocked() throws JSONException {
        return reconcileStartLocked() && reconcileStopLocked();
    }

    private boolean reconcileStartLocked() throws JSONException {
        Marker value = marker(); if (value == null) return true;
        PreparedStart prepared = value.prepared;
        if (value.ended && value.statisticsReady) return true;
        if (!prepared.statistics.identity.equals(main.getString(StatsRepository.NATIVE_OWNER_KEY, ""))) return false;
        if (!value.ended && (!main.getBoolean(AppModeManager.KEY_ARMED, false)
                || !prepared.pactIdentity.isEmpty() && !prepared.pactIdentity.equals(main.getString("commitment_session_identity", "")))) return false;
        if (value.statisticsReady) return prepared.statistics.identity.equals(stats().activeSessionIdentity());
        if (!flushMain()) return false;
        boolean saved = value.ended ? stats().finishNativeSession(prepared.statistics, value.end.wall, value.durationSeconds)
                : stats().ensureNativeSession(prepared.statistics);
        if (!saved) return false;
        SharedPreferences.Editor edit = main.edit().putString(MAIN_KEY, RemoteProtocol.canonical(
                new Marker(prepared, value.ended, true, value.end, value.durationSeconds).json()));
        if (value.ended) edit.remove(StatsRepository.NATIVE_OWNER_KEY);
        // Readiness is a recomputable cache; the required main marker and statistics commits already succeeded.
        edit.commit(); return true;
    }

    public static boolean reconcile(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            try { return get(context).reconcileLocked(); }
            catch (JSONException | RuntimeException invalid) { return false; }
        }
    }
    public static boolean beforeLocalStart(Context context) { return reconcile(context); }
    public static boolean handlesStatisticsEnd(Context context) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            try {
                RemoteSessionNative owner = get(context); Marker value = owner.marker();
                if (value == null || !value.ended) return false;
                String active = owner.stats().activeSessionIdentity();
                return active.isEmpty() || active.equals(value.prepared.statistics.identity);
            } catch (JSONException | RuntimeException invalid) { return false; }
        }
    }
    public static void appendLocalEnd(Context context, SharedPreferences.Editor edit) {
        synchronized (RuntimeSessionIdentity.LOCK) {
            try {
                RemoteSessionNative owner = get(context); Marker value = owner.marker();
                if (value == null || value.ended || !value.prepared.statistics.identity.equals(
                        owner.main.getString(StatsRepository.NATIVE_OWNER_KEY, ""))) return;
                String currentStatistics = owner.stats().activeSessionIdentity();
                if (!currentStatistics.isEmpty() && !currentStatistics.equals(value.prepared.statistics.identity)) return;
                if (!value.prepared.pactIdentity.isEmpty() && !value.prepared.pactIdentity.equals(
                        owner.main.getString("commitment_session_identity", ""))) return;
                RemoteSessionClock.Sample end = RemoteSessionClock.local(); PreparedStart prepared = value.prepared;
                long duration = end.boot >= 0 && end.boot == prepared.boot && end.elapsed >= prepared.startedElapsed
                        ? (end.elapsed - prepared.startedElapsed) / 1000
                        : end.wall >= prepared.statistics.startedAt ? (end.wall - prepared.statistics.startedAt) / 1000 : 0;
                edit.putString(MAIN_KEY, RemoteProtocol.canonical(new Marker(prepared, true, false, end, duration).json()));
            } catch (JSONException | RuntimeException invalid) {
                // An unparseable old marker must not prevent an explicit local stop or create a new session.
            }
        }
    }
    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid native UUID");
    }
    private static int boot(JSONObject value, String key) throws JSONException {
        long boot = RemoteProtocol.integer(value, key);
        if (boot < -1 || boot > Integer.MAX_VALUE) throw new JSONException("Invalid boot identity");
        return (int) boot;
    }
}
