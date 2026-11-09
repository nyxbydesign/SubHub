package com.subhub.app.stats;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import com.subhub.app.detection.TrackedObject;
import com.subhub.app.censor.engine.CensorEngine;
import com.subhub.app.censor.engine.SceneEngine;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Process-safe local counters compatible with the recovered statistics preference store. */
public final class StatsRepository {
    public static final String PREFS_NAME = "betablocker_stats";
    public static final String NATIVE_OWNER_KEY = "remote_session_stats_owner_v1";
    private static final String NATIVE_STARTED = "remote_native_started_identity";
    private static final String NATIVE_STARTED_FACT = "remote_native_started_fact";
    private static final String NATIVE_ENDED = "remote_native_ended_identity";
    private static final String KEY_ACTIVE_DATES = "active_dates";
    private static final String KEY_ALL_CATEGORIES_CENSORS = "all_categories_censors";
    private static final String KEY_BORDER_COLOR_CHANGED = "border_color_changed";
    private static final String KEY_BORDER_EFFECTS_TRIED = "border_effects_tried";
    private static final String KEY_BROWSER_SESSIONS = "browser_sessions_count";
    private static final String KEY_BROWSER_PAGES = "browser_pages_visited";
    private static final String KEY_CENSOR_STYLES_TRIED = "censor_styles_tried";
    private static final String KEY_CENSOR_STYLE_CHANGES = "censor_style_changes";
    private static final String KEY_CURRENT_STREAK = "current_streak";
    private static final String KEY_CUSTOM_PHRASES_COUNT = "custom_phrases_count";
    private static final String KEY_EXPORTED_IMAGES = "exported_images_count";
    private static final String KEY_LAST_SESSION_DATE = "last_session_date";
    private static final String KEY_LONGEST_SESSION = "longest_session_seconds";
    private static final String KEY_PEAK_SESSION_BLOCKS = "peak_session_blocks";
    private static final String KEY_SESSIONS_COUNT = "sessions_count";
    private static final String KEY_SESSION_HISTORY = "session_history";
    private static final String KEY_ACTIVE_SESSION_START = "active_session_start_ms";
    static final String ACTIVE_SESSION_ZONE = "active_session_zone_v1";
    private static final String KEY_ACTIVE_SESSION_BLOCKS = "active_session_blocks";
    private static final String KEY_TOTAL_BLOCKS = "total_blocks_all_time";
    private static final String KEY_TOTAL_PROTECTED_TIME = "total_protected_time";
    private static final String KEY_TOTAL_SESSION_TIME = "total_session_time";
    private static final String KEY_SUBLIMINAL_IMPRESSIONS = "subliminal_impressions";
    private static final String KEY_ACTIVE_SESSION_SUBLIMINALS = "active_session_subliminals";
    private static final String KEY_LIMITED_APP_MILLIS = "limited_app_millis";
    private static final String KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS =
            "active_session_limited_app_millis";
    private static final String KEY_LIMIT_INTERVENTIONS = "limit_interventions";
    private static final String KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS =
            "active_session_limit_interventions";
    private static final String KEY_TRIBUTE_EVENTS = "tribute_events";
    private static final String KEY_ACTIVE_SESSION_TRIBUTE_EVENTS =
            "active_session_tribute_events";
    private static final String KEY_TRIBUTE_CENTS = "tribute_cents";
    private static final String KEY_ACTIVE_SESSION_TRIBUTE_CENTS =
            "active_session_tribute_cents";
    private static final String KEY_TAMPER_EVENTS = "tamper_events";
    private static final String KEY_ACTIVE_SESSION_TAMPER_EVENTS =
            "active_session_tamper_events";
    private static final String KEY_POPUP_IMPRESSIONS = "popup_impressions";
    private static final String KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS =
            "active_session_popup_impressions";
    private static final int SESSION_HISTORY_MAX = 30;
    private static final Object SESSION_LOCK = new Object();
    private static final Set<Integer> SEEN_TRACK_IDS = new HashSet<>();
    private static final Set<String> ALL_CATEGORIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("genitals_female", "genitals_male", "breasts",
                    "buttocks", "anus", "face", "belly", "male_chest", "feet",
                    "armpits", "genitals_covered", "breasts_covered", "buttocks_covered",
                    "anus_covered", "belly_covered", "feet_covered", "armpits_covered")));
    private static long sessionStartMs;
    private static int sessionBlocks;
    private static volatile SessionStamp activeStamp;
    private static String memoryIdentity = "";

    /** Reporting time is deliberately not this process-local session's ABA authority. */
    public static final class SessionStamp {
        private SessionStamp() { }
        public boolean isCurrent() { return activeStamp == this; }
    }

    /** Nonblocking authority recheck for product main; restoration happens on its lookup worker. */
    public static SessionStamp currentSessionStamp() { return activeStamp; }

    public enum EvidenceStatus { COMMITTED, NO_NEW_EVIDENCE, REJECTED, UNACKNOWLEDGED }
    public static final class EvidenceRecord {
        public final EvidenceStatus status;
        public final int count;
        public final StatsSnapshot snapshot;
        private EvidenceRecord(EvidenceStatus status, int count, StatsSnapshot snapshot) {
            this.status = status; this.count = count; this.snapshot = snapshot;
        }
    }

    public SessionStamp sessionStamp() {
        synchronized (SESSION_LOCK) { restoreActiveSession(); return sessionStartMs == 0 ? null : activeStamp; }
    }

    private final SharedPreferences preferences;
    private final Context app;
    private final boolean recordDaily;

    public StatsRepository(Context context) { this(context, true); }

    StatsRepository(Context context, boolean recordDaily) {
        app = context.getApplicationContext();
        this.recordDaily = recordDaily;
        preferences = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (recordDaily) synchronized (SESSION_LOCK) { syncDaily(); }
    }

    public void startSession() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
        if (!com.subhub.app.remote.RemoteSessionNative.beforeLocalStart(app)) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs != 0) return;
            sessionStartMs = System.currentTimeMillis();
            sessionBlocks = 0;
            activeStamp = new SessionStamp();
            SEEN_TRACK_IDS.clear();
            writeDaily(preferences.edit()
                    .putLong(KEY_ACTIVE_SESSION_START, sessionStartMs)
                    .putString(ACTIVE_SESSION_ZONE, java.time.ZoneId.systemDefault().getId())
                    .putString("active_session_identity",java.util.UUID.randomUUID().toString())
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, 0)
                    .putLong(KEY_ACTIVE_SESSION_SUBLIMINALS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS + "_USD", 0L)
                    .putLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS, 0L)
                    .putLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS, 0L)
                    .putInt(KEY_SESSIONS_COUNT, preferences.getInt(KEY_SESSIONS_COUNT, 0) + 1)
                    );
            updateStreak();
            memoryIdentity = preferences.getString("active_session_identity", "");
            com.subhub.app.appmode.RuntimeSessionIdentity.changed();
            durationChanged();
        }
            }
    }

    public static final class NativeSession {
        public final String identity, zone;
        public final long startedAt;
        public NativeSession(String identity, long startedAt, String zone) {
            requireSessionIdentity(identity);
            if (startedAt <= 0) throw new IllegalArgumentException("Invalid native session clock");
            if (zone == null || zone.length() > 100) throw new IllegalArgumentException("Invalid session zone");
            java.time.ZoneId.of(zone);
            this.identity = identity; this.startedAt = startedAt; this.zone = zone;
        }
        private String fact() { return identity + ";" + startedAt + ";" + zone; }
    }

    /** Main's committed marker owns this UUID; recovery updates bookkeeping without arming protection. */
    public boolean ensureNativeSession(NativeSession session) {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                if (!session.identity.equals(app.getSharedPreferences(
                        com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0).getString(NATIVE_OWNER_KEY, "")))
                    return false;
                if (session.identity.equals(preferences.getString(NATIVE_STARTED, ""))) {
                    if (!session.fact().equals(preferences.getString(NATIVE_STARTED_FACT, ""))) return false;
                    boolean saved = flushNativeState(); refreshSessionMemory(); if (saved) syncDaily(); return saved;
                }
                if (session.identity.equals(preferences.getString(NATIVE_ENDED, ""))) return false;
                String active = preferences.getString("active_session_identity", "");
                if (!active.isEmpty() || preferences.getLong(KEY_ACTIVE_SESSION_START, 0) != 0) return false;
                SharedPreferences.Editor edit = clearActiveSession(preferences.edit())
                        .putString("active_session_identity", session.identity)
                        .putLong(KEY_ACTIVE_SESSION_START, session.startedAt)
                        .putString(ACTIVE_SESSION_ZONE, session.zone)
                        .putInt(KEY_ACTIVE_SESSION_BLOCKS, 0)
                        .putInt(KEY_SESSIONS_COUNT, (int) Math.min(Integer.MAX_VALUE,
                                Math.max(0L, preferences.getInt(KEY_SESSIONS_COUNT, 0)) + 1))
                        .putString(NATIVE_STARTED, session.identity)
                        .putString(NATIVE_STARTED_FACT, session.fact())
                        .putLong(DailyStatsStore.STAMP, session.startedAt).putString(DailyStatsStore.ZONE, session.zone);
                nativeStartStreak(edit, session);
                boolean saved = edit.commit(); refreshSessionMemory();
                if (saved) { syncDaily(); durationChanged(); }
                return saved;
            }
        }
    }

    public String activeSessionIdentity() {
        synchronized (SESSION_LOCK) { return preferences.getString("active_session_identity", ""); }
    }

    /** A known main-side start followed by End can be counted and closed without opening a phantom active session. */
    public boolean finishNativeSession(NativeSession session, long observedAt, long durationSeconds) {
        if (durationSeconds < 0) throw new IllegalArgumentException("Invalid session duration");
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                if (!session.identity.equals(app.getSharedPreferences(
                        com.subhub.app.settings.SettingsRepository.PREFERENCES_NAME, 0).getString(NATIVE_OWNER_KEY, ""))) return false;
                if (session.identity.equals(preferences.getString(NATIVE_STARTED, ""))) {
                    if (!session.fact().equals(preferences.getString(NATIVE_STARTED_FACT, ""))) return false;
                    return endSessionForIdentity(session.identity, observedAt, durationSeconds);
                }
                if (!preferences.getString("active_session_identity", "").isEmpty()
                        || preferences.getLong(KEY_ACTIVE_SESSION_START, 0) != 0
                        || session.identity.equals(preferences.getString(NATIVE_ENDED, ""))) return false;
                SessionEntry entry = new SessionEntry(session.startedAt, durationSeconds, 0)
                        .identified(session.identity, session.zone);
                SharedPreferences.Editor edit = clearActiveSession(preferences.edit())
                        .putInt(KEY_SESSIONS_COUNT, (int) Math.min(Integer.MAX_VALUE,
                                Math.max(0L, preferences.getInt(KEY_SESSIONS_COUNT, 0)) + 1))
                        .putString(NATIVE_STARTED, session.identity).putString(NATIVE_STARTED_FACT, session.fact())
                        .putString(NATIVE_ENDED, session.identity).putLong("remote_native_ended_at", observedAt)
                        .putLong(KEY_TOTAL_SESSION_TIME, safeAdd(preferences.getLong(KEY_TOTAL_SESSION_TIME, 0), durationSeconds))
                        .putLong(KEY_TOTAL_PROTECTED_TIME, safeAdd(preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0), durationSeconds))
                        .putLong(KEY_LONGEST_SESSION, Math.max(preferences.getLong(KEY_LONGEST_SESSION, 0), durationSeconds))
                        .putString(KEY_SESSION_HISTORY, appendedSessionHistory(entry))
                        .putLong(DailyStatsStore.STAMP, observedAt).putString(DailyStatsStore.ZONE, session.zone);
                nativeStartStreak(edit, session);
                boolean saved = edit.commit(); refreshSessionMemory();
                if (saved) { syncDaily(); durationChanged(); }
                return saved;
            }
        }
    }

    public boolean endSessionForIdentity(String expected) {
        requireSessionIdentity(expected);
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                long now = System.currentTimeMillis(), start = preferences.getLong(KEY_ACTIVE_SESSION_START, 0);
                return endSessionForIdentity(expected, now, now >= start && start > 0 ? (now - start) / 1000 : 0);
            }
        }
    }

    /** Exact active origin for a native transition's durable end witness. */
    public long activeSessionStartedAt(String expected) {
        requireSessionIdentity(expected);
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                return expected.equals(preferences.getString("active_session_identity", ""))
                        ? preferences.getLong(KEY_ACTIVE_SESSION_START, 0L) : 0L;
            }
        }
    }

    /** A lost marker-cache write may acknowledge existing history, but cannot invent an end time. */
    public boolean acknowledgeEndedSession(String expected) {
        requireSessionIdentity(expected);
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                if (!expected.equals(preferences.getString(NATIVE_ENDED, ""))
                        || !preferences.getString("active_session_identity", "").isEmpty()
                        || preferences.getLong(KEY_ACTIVE_SESSION_START, 0L) != 0L) return false;
                boolean saved = flushNativeState(); refreshSessionMemory(); if (saved) syncDaily(); return saved;
            }
        }
    }

    /** History, totals, the closure receipt and active-field removal share one checked preference commit. */
    public boolean endSessionForIdentity(String expected, long observedAt, long durationSeconds) {
        requireSessionIdentity(expected);
        if (durationSeconds < 0) throw new IllegalArgumentException("Invalid session duration");
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                if (expected.equals(preferences.getString(NATIVE_ENDED, ""))) {
                    boolean saved = flushNativeState(); refreshSessionMemory(); if (saved) syncDaily(); return saved;
                }
                if (!expected.equals(preferences.getString("active_session_identity", ""))) return false;
                long started = preferences.getLong(KEY_ACTIVE_SESSION_START, 0);
                if (started <= 0) return false;
                SessionEntry entry = new SessionEntry(started, durationSeconds,
                        Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0)),
                        activeLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS), activeLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS),
                        activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS), activeLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS),
                        activeLong(KEY_ACTIVE_SESSION_SUBLIMINALS), activeLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS),
                        activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS)).identified(expected, activeSessionZone());
                SharedPreferences.Editor edit = clearActiveSession(preferences.edit())
                        .putLong(KEY_TOTAL_SESSION_TIME, safeAdd(preferences.getLong(KEY_TOTAL_SESSION_TIME, 0), durationSeconds))
                        .putLong(KEY_TOTAL_PROTECTED_TIME, safeAdd(preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0), durationSeconds))
                        .putLong(KEY_LONGEST_SESSION, Math.max(preferences.getLong(KEY_LONGEST_SESSION, 0), durationSeconds))
                        .putString(KEY_SESSION_HISTORY, appendedSessionHistory(entry))
                        .putString(NATIVE_ENDED, expected).putLong("remote_native_ended_at", observedAt)
                        .putLong(DailyStatsStore.STAMP, observedAt)
                        .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId());
                boolean saved = edit.commit(); refreshSessionMemory();
                if (saved) { syncDaily(); durationChanged(); }
                return saved;
            }
        }
    }

    private void nativeStartStreak(SharedPreferences.Editor edit, NativeSession session) {
        String date = java.time.Instant.ofEpochMilli(session.startedAt).atZone(java.time.ZoneId.of(session.zone))
                .toLocalDate().toString();
        String previous = preferences.getString(KEY_LAST_SESSION_DATE, "");
        int old = Math.max(0, preferences.getInt(KEY_CURRENT_STREAK, 0));
        int streak = date.equals(previous) ? old
                : java.time.LocalDate.parse(date).minusDays(1).toString().equals(previous)
                        ? old == Integer.MAX_VALUE ? old : old + 1 : 1;
        edit.putInt(KEY_CURRENT_STREAK, streak).putString(KEY_LAST_SESSION_DATE, date);
    }

    private boolean flushNativeState() {
        // A new scalar forces a disk barrier even if a prior commit changed memory but was unacknowledged.
        return preferences.edit().putString("remote_native_commit_fence", java.util.UUID.randomUUID().toString()).commit();
    }

    /** A native operation with no recorded statistics UUID still needs a durable empty-state witness. */
    public boolean acknowledgeInactiveSession() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
            synchronized (SESSION_LOCK) {
                if (!preferences.getString("active_session_identity", "").isEmpty()
                        || preferences.getLong(KEY_ACTIVE_SESSION_START, 0L) != 0L) return false;
                boolean saved = flushNativeState(); refreshSessionMemory(); if (saved) syncDaily(); return saved;
            }
        }
    }

    private SharedPreferences.Editor clearActiveSession(SharedPreferences.Editor edit) {
        return edit.remove("active_session_identity").remove(ACTIVE_SESSION_ZONE).remove(KEY_ACTIVE_SESSION_START).remove(KEY_ACTIVE_SESSION_BLOCKS)
                .remove(KEY_ACTIVE_SESSION_SUBLIMINALS).remove(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS)
                .remove(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS).remove(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS)
                .remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS).remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS + "_USD")
                .remove(KEY_ACTIVE_SESSION_TAMPER_EVENTS).remove(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS);
    }

    private void refreshSessionMemory() {
        String identity = preferences.getString("active_session_identity", "");
        long start = preferences.getLong(KEY_ACTIVE_SESSION_START, 0);
        if (start <= 0) {
            sessionStartMs = 0; sessionBlocks = 0; activeStamp = null; memoryIdentity = ""; SEEN_TRACK_IDS.clear();
        } else {
            if (!identity.equals(memoryIdentity) || sessionStartMs != start) { activeStamp = new SessionStamp(); SEEN_TRACK_IDS.clear(); }
            sessionStartMs = start; sessionBlocks = Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0));
            memoryIdentity = identity;
        }
    }

    private static void requireSessionIdentity(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid native session identity");
    }

    public boolean onTracks(List<TrackedObject> tracks) {
        return recordTracks(tracks, null) > 0;
    }

    public boolean onTracks(List<TrackedObject> tracks, Set<String> enabledCategories) {
        return recordTracks(tracks, enabledCategories) > 0;
    }

    public boolean onTrackIds(List<Integer> trackIds, Set<String> enabledCategories) {
        return recordTrackIds(trackIds, enabledCategories) > 0;
    }

    /** Records new tracker IDs and returns their exact count for bounded downstream ledgers. */
    public int recordTracks(List<TrackedObject> tracks, Set<String> enabledCategories) {
        return recordTrackIds(trackIds(tracks), enabledCategories);
    }

    public int recordTrackIds(List<Integer> trackIds, Set<String> enabledCategories) {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return 0;
            int added = 0;
            for (Integer id : trackIds) if (id != null && SEEN_TRACK_IDS.add(id)) added++;
            if (added == 0) return 0;
            sessionBlocks += added;
            SharedPreferences.Editor edit = preferences.edit()
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, sessionBlocks)
                    .putLong(KEY_TOTAL_BLOCKS, preferences.getLong(KEY_TOTAL_BLOCKS, 0) + added);
            if (sessionBlocks > preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0)) {
                edit.putInt(KEY_PEAK_SESSION_BLOCKS, sessionBlocks);
            }
            Set<String> dates = mutableSet(KEY_ACTIVE_DATES);
            if (dates.add(today())) edit.putStringSet(KEY_ACTIVE_DATES, dates);
            if (enabledCategories != null && enabledCategories.containsAll(ALL_CATEGORIES)) {
                edit.putLong(KEY_ALL_CATEGORIES_CENSORS,
                        preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0) + added);
            }
            writeDaily(edit);
            return added;
        }
    }

    /**
     * Off-main, acknowledged counter write. The supplier may only claim source evidence, never
     * invoke wallet/UI/observers under SESSION_LOCK. Downstream work starts after this returns.
     * An unacknowledged commit consumed the claim and must not be replayed speculatively.
     */
    public EvidenceRecord recordEvidence(SessionStamp expected, Supplier<CensorEngine.EvidenceClaim> claimSource,
            Set<String> enabledCategories) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Evidence commit must be off main");
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (expected == null || activeStamp != expected || sessionStartMs == 0
                    || preferences.getLong(KEY_ACTIVE_SESSION_START, 0) != sessionStartMs) {
                return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            }
            CensorEngine.EvidenceClaim claim = claimSource.get();
            if (claim == null) return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            List<SceneEngine.AcceptedSubject> delivered = claim.takeFor(expected);
            if (delivered == null) return new EvidenceRecord(EvidenceStatus.REJECTED, 0, null);
            int count = delivered.size();
            if (count == 0) return new EvidenceRecord(EvidenceStatus.NO_NEW_EVIDENCE, 0, null);
            int nextBlocks = Math.addExact(sessionBlocks, count);
            long total = Math.addExact(preferences.getLong(KEY_TOTAL_BLOCKS, 0), count);
            SharedPreferences.Editor edit = preferences.edit()
                    .putInt(KEY_ACTIVE_SESSION_BLOCKS, nextBlocks).putLong(KEY_TOTAL_BLOCKS, total);
            if (nextBlocks > preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0)) edit.putInt(KEY_PEAK_SESSION_BLOCKS, nextBlocks);
            Set<String> dates = mutableSet(KEY_ACTIVE_DATES);
            if (dates.add(today())) edit.putStringSet(KEY_ACTIVE_DATES, dates);
            if (enabledCategories != null && enabledCategories.containsAll(ALL_CATEGORIES)) {
                edit.putLong(KEY_ALL_CATEGORIES_CENSORS,
                        Math.addExact(preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0), count));
            }
            edit.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                    .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId());
            boolean acknowledged;
            try { acknowledged = edit.commit(); }
            finally {
                // Android may publish the preference memory before a false/uncertain disk result.
                // Keep the process counter/history coherent with that observed memory, not a
                // fabricated rollback or durability acknowledgement. The claim remains consumed.
                sessionBlocks = Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0));
            }
            if (!acknowledged) return new EvidenceRecord(EvidenceStatus.UNACKNOWLEDGED, 0, null);
            syncDaily();
            return new EvidenceRecord(EvidenceStatus.COMMITTED, count, load());
        }
    }

    public void endSession() {
        synchronized (com.subhub.app.appmode.RuntimeSessionIdentity.LOCK) {
        if (com.subhub.app.remote.RemoteSessionNative.handlesStatisticsEnd(app)) {
            com.subhub.app.remote.RemoteSessionNative.reconcile(app); return;
        }
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            long started = sessionStartMs;
            long durationSeconds = Math.max(0, (System.currentTimeMillis() - started) / 1000);
            SharedPreferences.Editor edit = preferences.edit()
                    .putLong(KEY_TOTAL_SESSION_TIME,
                            preferences.getLong(KEY_TOTAL_SESSION_TIME, 0) + durationSeconds)
                    .putLong(KEY_TOTAL_PROTECTED_TIME,
                            preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0) + durationSeconds);
            if (durationSeconds > preferences.getLong(KEY_LONGEST_SESSION, 0)) {
                edit.putLong(KEY_LONGEST_SESSION, durationSeconds);
            }
            edit.apply();
            appendSessionHistory(new SessionEntry(started, durationSeconds, sessionBlocks,
                    activeLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS),
                    activeLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS),
                    activeLong(KEY_ACTIVE_SESSION_SUBLIMINALS),
                    activeLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS),
                    activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS)).identified(
                            preferences.getString("active_session_identity", ""), activeSessionZone()));
            preferences.edit().remove("active_session_identity").remove(ACTIVE_SESSION_ZONE).remove(KEY_ACTIVE_SESSION_START)
                    .remove(KEY_ACTIVE_SESSION_BLOCKS)
                    .remove(KEY_ACTIVE_SESSION_SUBLIMINALS)
                    .remove(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS)
                    .remove(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS)
                    .remove(KEY_ACTIVE_SESSION_TRIBUTE_CENTS + "_USD")
                    .remove(KEY_ACTIVE_SESSION_TAMPER_EVENTS)
                    .remove(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS).apply();
            sessionStartMs = 0;
            sessionBlocks = 0;
            activeStamp = null;
            memoryIdentity = "";
            com.subhub.app.appmode.RuntimeSessionIdentity.changed();
            SEEN_TRACK_IDS.clear();
            durationChanged();
        }
            }
    }

    public StatsSnapshot load() {
        return load(true);
    }

    StatsSnapshot retainedSnapshot() { return load(false); }

    boolean observedSessionActive() { return observedSessionStart() > 0; }

    private long observedSessionStart() {
        long start = preferences.getLong(KEY_ACTIVE_SESSION_START, 0L), now = System.currentTimeMillis();
        return start > 0 && start <= now && now - start <= 366L * 24L * 60L * 60L * 1000L ? start : 0;
    }

    private StatsSnapshot load(boolean restore) {
        synchronized (SESSION_LOCK) {
            if (restore) restoreActiveSession();
            long observedStart = restore ? sessionStartMs : observedSessionStart();
            long protectedSeconds = preferences.getLong(KEY_TOTAL_PROTECTED_TIME, 0);
            long totalSessionSeconds = preferences.getLong(KEY_TOTAL_SESSION_TIME, 0);
            long currentSessionSeconds = 0;
            if (observedStart > 0) {
                currentSessionSeconds = Math.max(0,
                        (System.currentTimeMillis() - observedStart) / 1000);
                protectedSeconds += currentSessionSeconds;
                totalSessionSeconds += currentSessionSeconds;
            }
            return new StatsSnapshot(preferences.getLong(KEY_TOTAL_BLOCKS, 0),
                    totalSessionSeconds, preferences.getInt(KEY_SESSIONS_COUNT, 0),
                    preferences.getInt(KEY_CURRENT_STREAK, 0),
                    preferences.getString(KEY_LAST_SESSION_DATE, ""), protectedSeconds,
                    restore ? sessionBlocks : observedStart > 0 ? preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0) : 0,
                    preferences.getInt(KEY_PEAK_SESSION_BLOCKS, 0),
                    preferences.getLong(KEY_LONGEST_SESSION, 0),
                    preferences.getInt(KEY_BROWSER_SESSIONS, 0),
                    preferences.getInt(KEY_BROWSER_PAGES, 0),
                    getLongCompat(KEY_EXPORTED_IMAGES),
                    preferences.getInt(KEY_CUSTOM_PHRASES_COUNT, 0),
                    preferences.getInt(KEY_CENSOR_STYLE_CHANGES, 0),
                    preferences.getBoolean(KEY_BORDER_COLOR_CHANGED, false),
                    mutableSet(KEY_CENSOR_STYLES_TRIED), mutableSet(KEY_BORDER_EFFECTS_TRIED),
                    mutableSet(KEY_ACTIVE_DATES),
                    preferences.getLong(KEY_ALL_CATEGORIES_CENSORS, 0),
                    currentSessionSeconds,
                    preferences.getLong(KEY_SUBLIMINAL_IMPRESSIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_SUBLIMINALS),
                    preferences.getLong(KEY_LIMITED_APP_MILLIS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS),
                    preferences.getLong(KEY_LIMIT_INTERVENTIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS),
                    preferences.getLong(KEY_TRIBUTE_EVENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS),
                    preferences.getLong(KEY_TRIBUTE_CENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TRIBUTE_CENTS),
                    preferences.getLong(KEY_TAMPER_EVENTS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS),
                    preferences.getLong(KEY_POPUP_IMPRESSIONS, 0L),
                    activeLong(KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS));
        }
    }

    private void restoreActiveSession() {
        String identity = preferences.getString("active_session_identity", "");
        if (sessionStartMs != 0 && identity.equals(memoryIdentity)) return;
        long stored = preferences.getLong(KEY_ACTIVE_SESSION_START, 0);
        long now = System.currentTimeMillis();
        // Discard impossible/corrupt timestamps instead of displaying an unbounded timer.
        boolean nativeKnown = !identity.isEmpty() && identity.equals(preferences.getString(NATIVE_STARTED, ""));
        if (stored <= 0 || !nativeKnown && (stored > now
                || now - stored > 366L * 24L * 60L * 60L * 1000L)) {
            if (stored != 0) preferences.edit().remove("active_session_identity").remove(ACTIVE_SESSION_ZONE).remove(KEY_ACTIVE_SESSION_START)
                    .remove(KEY_ACTIVE_SESSION_BLOCKS).apply();
            sessionStartMs = 0; sessionBlocks = 0; activeStamp = null; memoryIdentity = ""; SEEN_TRACK_IDS.clear();
            return;
        }
        sessionStartMs = stored;
        sessionBlocks = Math.max(0, preferences.getInt(KEY_ACTIVE_SESSION_BLOCKS, 0));
        activeStamp = new SessionStamp();
        memoryIdentity = identity;
    }

    public List<SessionEntry> getSessionHistory() {
        return parseSessionHistory(preferences.getString(KEY_SESSION_HISTORY, ""));
    }

    static List<SessionEntry> parseSessionHistory(String raw) {
        List<SessionEntry> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;
        for (String encoded : raw.split(";")) {
            String[] parts = encoded.split(",", -1);
            try {
                boolean versionThree = parts.length == 13 && "3".equals(parts[0]);
                boolean versionTwo = parts.length == 11 && "2".equals(parts[0]);
                boolean detailed = versionTwo || versionThree;
                if (!detailed && parts.length != 3) continue;
                int offset = detailed ? 1 : 0;
                long start = Long.parseLong(parts[offset]);
                long duration = Long.parseLong(parts[offset + 1]);
                int blocks = Integer.parseInt(parts[offset + 2]);
                if (start > 0 && duration >= 0 && blocks >= 0) {
                    SessionEntry entry = detailed
                            ? new SessionEntry(start, duration, blocks,
                            Long.parseLong(parts[4]), Long.parseLong(parts[5]),
                            Long.parseLong(parts[6]), Long.parseLong(parts[7]),
                            Long.parseLong(parts[8]), Long.parseLong(parts[9]),
                            Long.parseLong(parts[10]))
                            : new SessionEntry(start, duration, blocks);
                    result.add(versionThree ? entry.identified(parts[11], parts[12]) : entry);
                }
            } catch (IllegalArgumentException | java.time.DateTimeException ignored) {
                // Ignore malformed legacy entries without discarding the remaining history.
            }
        }
        return Collections.unmodifiableList(result);
    }

    public void recordCensorStyleTried(String style) { addToSet(KEY_CENSOR_STYLES_TRIED, style); }
    public void recordBorderEffectTried(String effect) { addToSet(KEY_BORDER_EFFECTS_TRIED, effect); }
    public void incrementCensorStyleChanges() {
        preferences.edit().putInt(KEY_CENSOR_STYLE_CHANGES,
                preferences.getInt(KEY_CENSOR_STYLE_CHANGES, 0) + 1).apply();
    }
    public void setBorderColorChanged() {
        preferences.edit().putBoolean(KEY_BORDER_COLOR_CHANGED, true).apply();
    }
    public void setCustomPhrasesCount(int count) {
        preferences.edit().putInt(KEY_CUSTOM_PHRASES_COUNT, Math.max(0, count)).apply();
    }
    public void addExportedImages(int count) {
        if (count <= 0) return;
        preferences.edit().putLong(KEY_EXPORTED_IMAGES,
                getLongCompat(KEY_EXPORTED_IMAGES) + count).apply();
    }
    public void recordSubliminalImpression() {
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            writeDaily(preferences.edit()
                    .putLong(KEY_SUBLIMINAL_IMPRESSIONS,
                            preferences.getLong(KEY_SUBLIMINAL_IMPRESSIONS, 0L) + 1L)
                    .putLong(KEY_ACTIVE_SESSION_SUBLIMINALS,
                            preferences.getLong(KEY_ACTIVE_SESSION_SUBLIMINALS, 0L) + 1L)
                    );
        }
    }
    public void recordLimitedAppUsage(long elapsedMillis) {
        incrementDuringSession(KEY_LIMITED_APP_MILLIS,
                KEY_ACTIVE_SESSION_LIMITED_APP_MILLIS, elapsedMillis);
    }
    public void recordLimitIntervention() {
        incrementDuringSession(KEY_LIMIT_INTERVENTIONS,
                KEY_ACTIVE_SESSION_LIMIT_INTERVENTIONS, 1L);
    }
    public void recordTributeEvent(int amountCents, boolean tamper) {
        recordTributeEvent(amountCents, tamper, "EUR");
    }

    private static String currencyKey(String key, String currency) {
        com.subhub.app.penance.WalletCurrency.requireSupported(currency);
        return "EUR".equals(currency) ? key : key + "_" + currency;
    }

    public long tributeCents(String currency, boolean currentSession) {
        synchronized (SESSION_LOCK) {
            if (currentSession) restoreActiveSession();
            String key = currencyKey(currentSession ? KEY_ACTIVE_SESSION_TRIBUTE_CENTS
                    : KEY_TRIBUTE_CENTS, currency);
            return currentSession ? activeLong(key) : preferences.getLong(key, 0L);
        }
    }

    public void recordTributeEvent(int amountCents, boolean tamper, String currency) {
        String totalKey = currencyKey(KEY_TRIBUTE_CENTS, currency);
        String sessionKey = currencyKey(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, currency);
        if (amountCents <= 0) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            SharedPreferences.Editor editor = preferences.edit()
                    .putLong(KEY_TRIBUTE_EVENTS,
                            safeAdd(preferences.getLong(KEY_TRIBUTE_EVENTS, 0L), 1L))
                    .putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS,
                            safeAdd(activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS), 1L))
                    .putLong(totalKey,
                            safeAdd(preferences.getLong(totalKey, 0L), amountCents))
                    .putLong(sessionKey,
                            safeAdd(activeLong(sessionKey), amountCents));
            if (tamper) {
                editor.putLong(KEY_TAMPER_EVENTS,
                                safeAdd(preferences.getLong(KEY_TAMPER_EVENTS, 0L), 1L))
                        .putLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS,
                                safeAdd(activeLong(KEY_ACTIVE_SESSION_TAMPER_EVENTS), 1L));
            }
            writeDaily(editor);
        }
    }
    public void recordPopupImpression() {
        incrementDuringSession(KEY_POPUP_IMPRESSIONS,
                KEY_ACTIVE_SESSION_POPUP_IMPRESSIONS, 1L);
    }
    private void updateStreak() {
        String today = today();
        String previous = preferences.getString(KEY_LAST_SESSION_DATE, "");
        int streak;
        if (today.equals(previous)) streak = preferences.getInt(KEY_CURRENT_STREAK, 0);
        else if (LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                .equals(previous)) streak = preferences.getInt(KEY_CURRENT_STREAK, 0) + 1;
        else streak = 1;
        preferences.edit().putInt(KEY_CURRENT_STREAK, streak)
                .putString(KEY_LAST_SESSION_DATE, today).apply();
    }

    private String activeSessionZone() {
        return preferences.getString(ACTIVE_SESSION_ZONE, java.time.ZoneId.systemDefault().getId());
    }

    private void appendSessionHistory(SessionEntry entry) {
        preferences.edit().putString(KEY_SESSION_HISTORY, appendedSessionHistory(entry)).apply();
    }

    private String appendedSessionHistory(SessionEntry entry) {
        List<String> entries = new ArrayList<>();
        String raw = preferences.getString(KEY_SESSION_HISTORY, "");
        if (raw != null) for (String item : raw.split(";")) {
            if (!item.trim().isEmpty()) entries.add(item);
        }
        entries.add(String.join(",", "3", Long.toString(entry.startMillis),
                Long.toString(entry.durationSeconds), Integer.toString(entry.blocks),
                Long.toString(entry.limitedAppMillis),
                Long.toString(entry.limitInterventions),
                Long.toString(entry.tributeEvents), Long.toString(entry.tributeCents),
                Long.toString(entry.subliminals), Long.toString(entry.popupImpressions),
                Long.toString(entry.tamperEvents), entry.identity, entry.zone));
        while (entries.size() > SESSION_HISTORY_MAX) entries.remove(0);
        return String.join(";", entries);
    }

    private void addToSet(String key, String value) {
        if (value == null || value.trim().isEmpty()) return;
        Set<String> values = mutableSet(key);
        if (values.add(value.trim())) preferences.edit().putStringSet(key, values).apply();
    }

    private Set<String> mutableSet(String key) {
        Set<String> stored = preferences.getStringSet(key, Collections.emptySet());
        return new LinkedHashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    private long getLongCompat(String key) {
        Object value = preferences.getAll().get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private long activeLong(String key) {
        return Math.max(0L, preferences.getLong(key, 0L));
    }

    private void incrementDuringSession(String totalKey, String activeKey, long amount) {
        if (amount <= 0L) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            if (sessionStartMs == 0) return;
            writeDaily(preferences.edit()
                    .putLong(totalKey, safeAdd(preferences.getLong(totalKey, 0L), amount))
                    .putLong(activeKey, safeAdd(activeLong(activeKey), amount))
                    );
        }
    }

    /**
     * A committed source ledger event always contributes to lifetime totals, but never to a
     * different newly armed session. No historical session is guessed from a wall-clock timestamp.
     */
    public void recordSourceTribute(SessionStamp expected, int amountCents, String currency) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Source tribute commit must be off main");
        if (expected == null || amountCents <= 0) return;
        synchronized (SESSION_LOCK) {
            restoreActiveSession();
            String totalKey = currencyKey(KEY_TRIBUTE_CENTS, currency);
            SharedPreferences.Editor edit = preferences.edit()
                    .putLong(KEY_TRIBUTE_EVENTS, safeAdd(preferences.getLong(KEY_TRIBUTE_EVENTS, 0), 1))
                    .putLong(totalKey, safeAdd(preferences.getLong(totalKey, 0), amountCents));
            if (activeStamp == expected && sessionStartMs != 0) {
                String sessionKey = currencyKey(KEY_ACTIVE_SESSION_TRIBUTE_CENTS, currency);
                edit.putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS, safeAdd(activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS), 1))
                        .putLong(sessionKey, safeAdd(activeLong(sessionKey), amountCents));
            }
            edit.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                    .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId());
            if (!edit.commit()) throw new IllegalStateException("Source tribute store unacknowledged");
            syncDaily();
        }
    }

    /** A request's durable debit can replay its projection without counting it twice or inventing a session. */
    public void recordRequestTributeReceipt(String ledgerId, int amountCents, String currency, SessionStamp expected) {
        if (amountCents <= 0) return;
        String receipt = "request_tribute_receipt_" + java.util.UUID.nameUUIDFromBytes(
                ledgerId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        synchronized (SESSION_LOCK) {
            if (preferences.contains(receipt)) {
                if (!preferences.edit().commit()) throw new IllegalStateException("Request tribute statistics are not saved");
                return;
            }
            restoreActiveSession();
            String totalKey = currencyKey(KEY_TRIBUTE_CENTS,currency);
            SharedPreferences.Editor edit = preferences.edit().putBoolean(receipt,true)
                    .putLong(KEY_TRIBUTE_EVENTS,safeAdd(preferences.getLong(KEY_TRIBUTE_EVENTS,0),1))
                    .putLong(totalKey,safeAdd(preferences.getLong(totalKey,0),amountCents));
            if (expected != null && expected == activeStamp && sessionStartMs != 0) {
                String sessionKey = currencyKey(KEY_ACTIVE_SESSION_TRIBUTE_CENTS,currency);
                edit.putLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS,safeAdd(activeLong(KEY_ACTIVE_SESSION_TRIBUTE_EVENTS),1))
                        .putLong(sessionKey,safeAdd(activeLong(sessionKey),amountCents));
            }
            edit.putLong(DailyStatsStore.STAMP,System.currentTimeMillis())
                    .putString(DailyStatsStore.ZONE,java.time.ZoneId.systemDefault().getId());
            if (!edit.commit()) throw new IllegalStateException("Request tribute statistics were not saved");
            syncDaily();
        }
    }

    private void writeDaily(SharedPreferences.Editor editor) {
        editor.putLong(DailyStatsStore.STAMP, System.currentTimeMillis())
                .putString(DailyStatsStore.ZONE, java.time.ZoneId.systemDefault().getId()).apply();
        syncDaily();
    }
    private void durationChanged() { if (recordDaily) DailyDurationRecorder.changed(); }
    private void syncDaily() {
        if (!recordDaily) return;
        try { DailyStatsStore.get(app).queueStats(preferences); }
        catch (RuntimeException unavailable) { android.util.Log.w("DailyStats", "Counter checkpoint pending"); }
    }
    /** Reconcile completed photo jobs without double-counting them after a process restart. */
    public void reconcileExportedPhotos(long completed) {
        synchronized (SESSION_LOCK) {
            long previous = preferences.getLong("export_job_photos_seen", 0);
            if (completed <= previous) return;
            preferences.edit().putLong(KEY_EXPORTED_IMAGES, safeAdd(getLongCompat(KEY_EXPORTED_IMAGES), completed - previous))
                    .putLong("export_job_photos_seen", completed).commit();
        }
    }

    private static long safeAdd(long current, long amount) {
        return current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount;
    }

    private static List<Integer> trackIds(List<TrackedObject> tracks) {
        List<Integer> ids = new ArrayList<>(tracks.size());
        // Render immediately, but only count a region after temporal confirmation. This keeps
        // single-frame detector noise and repeated screenshot refreshes out of stats and money.
        for (TrackedObject track : tracks) {
            if (track.isConfirmed() && track.getFramesMissing() == 0) ids.add(track.getId());
        }
        return ids;
    }
    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static final class SessionEntry {
        private final long startMillis;
        private final long durationSeconds;
        private final int blocks;
        private final long limitedAppMillis;
        private final long limitInterventions;
        private final long tributeEvents;
        private final long tributeCents;
        private final long subliminals;
        private final long popupImpressions;
        private final long tamperEvents;
        private String identity = "", zone = "";
        SessionEntry identified(String identity, String zone) {
            if (!identity.isEmpty()) requireSessionIdentity(identity);
            if (!zone.isEmpty()) java.time.ZoneId.of(zone);
            this.identity = identity; this.zone = zone; return this;
        }
        String identity() { return identity; }
        String zone() { return zone; }
        public SessionEntry(long startMillis, long durationSeconds, int blocks) {
            this(startMillis, durationSeconds, blocks, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        public SessionEntry(long startMillis, long durationSeconds, int blocks,
                long limitedAppMillis, long limitInterventions, long tributeEvents,
                long tributeCents, long subliminals, long popupImpressions,
                long tamperEvents) {
            this.startMillis = startMillis;
            this.durationSeconds = durationSeconds;
            this.blocks = blocks;
            this.limitedAppMillis = Math.max(0L, limitedAppMillis);
            this.limitInterventions = Math.max(0L, limitInterventions);
            this.tributeEvents = Math.max(0L, tributeEvents);
            this.tributeCents = Math.max(0L, tributeCents);
            this.subliminals = Math.max(0L, subliminals);
            this.popupImpressions = Math.max(0L, popupImpressions);
            this.tamperEvents = Math.max(0L, tamperEvents);
        }
        public long getStartMillis() { return startMillis; }
        public long getDurationSeconds() { return durationSeconds; }
        public int getBlocks() { return blocks; }
        public long getLimitedAppMillis() { return limitedAppMillis; }
        public long getLimitInterventions() { return limitInterventions; }
        public long getTributeEvents() { return tributeEvents; }
        public long getTributeCents() { return tributeCents; }
        public long getSubliminals() { return subliminals; }
        public long getPopupImpressions() { return popupImpressions; }
        public long getTamperEvents() { return tamperEvents; }
        public long getActivityEvents() {
            return blocks + limitInterventions + tributeEvents + subliminals + popupImpressions;
        }
    }
}
