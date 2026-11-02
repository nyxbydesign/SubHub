package com.subhub.app.remote;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import androidx.test.platform.app.InstrumentationRegistry;
import com.subhub.app.appmode.AppModeManager;
import com.subhub.app.appmode.RuntimeSessionIdentity;
import com.subhub.app.appmode.SessionExpiryAlarms;
import com.subhub.app.appmode.SessionExpiryEffects;
import com.subhub.app.penance.PaidPauseManager;
import com.subhub.app.penance.PenanceManager;
import com.subhub.app.security.SubFeaturePolicy;
import com.subhub.app.service.ScreenCaptureService;
import com.subhub.app.service.ScreenshotAccessibilityService;
import com.subhub.app.settings.SettingsRepository;
import com.subhub.app.stats.StatsRepository;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Host reboot separates a prepared persisted timer from actual cold BOOT_COMPLETED restoration. */
public final class RemoteSessionBootRebootAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final List<String> MAIN = Arrays.asList(AppModeManager.KEY_ARMED, "media_projection_session_desired",
            "commitment_session_identity", "commitment_started_at", "commitment_ends_at", "commitment_duration",
            "commitment_hide_countdown", "commitment_range_min", "commitment_range_max", "commitment_ends_elapsed", "commitment_boot");
    private static final List<String> STATS = Arrays.asList("active_session_identity", "active_session_start_ms");
    private static final List<String> PAUSE = Arrays.asList("paid_pause_session_identity", "paid_pause_active_until", "paid_pause_rearm", PaidPauseManager.KEY_ENABLED);
    private SharedPreferences main() { return context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, 0); }
    private SharedPreferences stats() { return context.getSharedPreferences(StatsRepository.PREFS_NAME, 0); }
    private SharedPreferences pause() { return context.getSharedPreferences(PenanceManager.PREFS_NAME, 0); }
    private AtomicFile backup() {
        String id = InstrumentationRegistry.getArguments().getString("storage_id", "");
        org.junit.Assume.assumeTrue("Explicit isolated reboot fixture required", id.matches("receiverboot_[A-Za-z0-9_]{1,80}"));
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "receiver-lifetime-" + id + ".json"));
    }
    private static JSONObject capture(SharedPreferences preferences, List<String> keys) throws Exception {
        Map<String, ?> all = preferences.getAll(); JSONObject result = new JSONObject();
        for (String key : keys) {
            if (!all.containsKey(key)) continue;
            Object value = all.get(key); String type; Object encoded;
            if (value instanceof String) { type = "string"; encoded = value; }
            else if (value instanceof Boolean) { type = "boolean"; encoded = value; }
            else if (value instanceof Integer) { type = "int"; encoded = value.toString(); }
            else if (value instanceof Long) { type = "long"; encoded = value.toString(); }
            else if (value instanceof Float) { type = "float"; encoded = value.toString(); }
            else if (value instanceof Set) {
                type = "strings"; List<String> strings = new ArrayList<>();
                for (Object item : (Set<?>) value) strings.add((String) item); Collections.sort(strings); encoded = new JSONArray(strings);
            } else throw new AssertionError("Unsupported backup preference type");
            result.put(key, new JSONObject().put("type", type).put("value", encoded));
        }
        return result;
    }
    private static void restore(SharedPreferences preferences, List<String> keys, JSONObject snapshot) throws Exception {
        SharedPreferences.Editor edit = preferences.edit(); for (String key : keys) edit.remove(key);
        java.util.Iterator<String> names = snapshot.keys();
        while (names.hasNext()) {
            String key = names.next(); assertTrue(keys.contains(key)); JSONObject value = snapshot.getJSONObject(key);
            switch (value.getString("type")) {
                case "string": edit.putString(key, value.getString("value")); break;
                case "boolean": edit.putBoolean(key, value.getBoolean("value")); break;
                case "int": edit.putInt(key, Integer.parseInt(value.getString("value"))); break;
                case "long": edit.putLong(key, Long.parseLong(value.getString("value"))); break;
                case "float": edit.putFloat(key, Float.parseFloat(value.getString("value"))); break;
                case "strings": {
                    Set<String> strings = new java.util.HashSet<>(); JSONArray values = value.getJSONArray("value");
                    for (int i = 0; i < values.length(); i++) strings.add(values.getString(i)); edit.putStringSet(key, strings); break;
                }
                default: throw new AssertionError("Unknown backup preference type");
            }
        }
        assertTrue(edit.commit()); assertEquals(RemoteProtocol.canonical(snapshot), RemoteProtocol.canonical(capture(preferences, keys)));
    }
    private static String digest(SharedPreferences preferences) throws Exception {
        List<String> keys = new ArrayList<>(preferences.getAll().keySet()); Collections.sort(keys);
        return RemoteProtocol.sha256(RemoteProtocol.canonical(capture(preferences, keys)).getBytes(StandardCharsets.UTF_8));
    }
    private static void write(AtomicFile file, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8); assertTrue(bytes.length <= 2 * 1024 * 1024);
        FileOutputStream output = file.startWrite();
        try { output.write(bytes); file.finishWrite(output); }
        catch (Exception failure) { file.failWrite(output); throw failure; }
    }
    private JSONObject read(AtomicFile file) throws Exception {
        assertTrue(file.getBaseFile().length() <= 2 * 1024 * 1024);
        try (java.io.InputStream input = file.openRead(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); assertTrue(output.size() <= 2 * 1024 * 1024); }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    private void restoreFixture(AtomicFile file, JSONObject saved) throws Exception {
        SessionExpiryEffects.whenDispatched().get(5, TimeUnit.SECONDS); SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
        SessionExpiryAlarms.Kind kind = SessionExpiryAlarms.Kind.valueOf(saved.getString("kind"));
        PendingIntent pending = PendingIntent.getBroadcast(context, kind == SessionExpiryAlarms.Kind.PACT ? 8317 : 8349,
                SessionExpiryAlarms.intent(context, kind, saved.getString("identity")), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending != null) { context.getSystemService(AlarmManager.class).cancel(pending); pending.cancel(); }
        synchronized (RuntimeSessionIdentity.LOCK) {
            restore(main(), MAIN, saved.getJSONObject("main")); restore(stats(), STATS, saved.getJSONObject("stats"));
            restore(pause(), PAUSE, saved.getJSONObject("pause")); RuntimeSessionIdentity.changed();
        }
        assertEquals("Historical statistics changed during timer-only boot fixture", saved.getString("statsDigest"), digest(stats()));
        file.delete(); assertFalse(file.getBaseFile().exists());
    }
    @Test public void seedPersistedTimerBeforeHostReboot() throws Exception {
        AtomicFile file = backup(); assertFalse("Do not overwrite a pending recovery asset", file.getBaseFile().exists());
        assertFalse(new File(file.getBaseFile() + ".bak").exists());
        RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
        assertFalse(RuntimeSessionIdentity.observe(context, now).active);
        assertFalse(ScreenCaptureService.isRunning()); assertFalse(ScreenshotAccessibilityService.isRunning());
        assertEquals("", main().getString(RemoteSessionNative.MAIN_KEY, ""));
        assertEquals("", main().getString(RemoteSessionNative.STOP_MAIN_KEY, ""));
        assertEquals("", main().getString(StatsRepository.NATIVE_OWNER_KEY, ""));
        assertFalse(context.getSharedPreferences("subhub_hardcore_auto_pay", 0).getBoolean("enabled", false));
        String kindName = InstrumentationRegistry.getArguments().getString("boot_kind", "");
        assertTrue(kindName.equals("PACT") || kindName.equals("PAUSE")); SessionExpiryAlarms.Kind kind = SessionExpiryAlarms.Kind.valueOf(kindName);
        if (kind == SessionExpiryAlarms.Kind.PAUSE) {
            assertTrue("Existing synthetic wallet consent required", SubFeaturePolicy.chosen(context, "wallet"));
            assertTrue("Existing wallet migration must already be complete", pause().getBoolean(PenanceManager.KEY_RULE_PARTICIPATION_MIGRATED, false));
        }
        String identity = UUID.randomUUID().toString(), statistics = UUID.randomUUID().toString();
        long duration = kind == SessionExpiryAlarms.Kind.PACT ? 3600000 : 900000, ends = now.wall + duration;
        JSONObject saved = new JSONObject().put("schema", 1).put("kind", kindName).put("identity", identity).put("endsAt", ends)
                .put("boot", now.boot).put("pid", android.os.Process.myPid()).put("statsIdentity", kind == SessionExpiryAlarms.Kind.PACT ? statistics : "")
                .put("statsDigest", digest(stats())).put("main", capture(main(), MAIN)).put("stats", capture(stats(), STATS))
                .put("pause", capture(pause(), PAUSE)).put("phase", "BACKED_UP");
        write(file, saved); boolean complete = false;
        try {
            synchronized (RuntimeSessionIdentity.LOCK) {
                SharedPreferences.Editor main = main().edit(); for (String key : MAIN) main.remove(key);
                main.putBoolean(AppModeManager.KEY_ARMED, kind == SessionExpiryAlarms.Kind.PACT).putBoolean("media_projection_session_desired", false);
                SharedPreferences.Editor stats = stats().edit().remove("active_session_identity").remove("active_session_start_ms");
                SharedPreferences.Editor pause = pause().edit(); for (String key : PAUSE) pause.remove(key);
                if (kind == SessionExpiryAlarms.Kind.PACT) {
                    main.putString("commitment_session_identity", identity).putLong("commitment_started_at", now.wall)
                            .putLong("commitment_ends_at", ends).putLong("commitment_duration", duration)
                            .putLong("commitment_ends_elapsed", now.elapsed + duration).putInt("commitment_boot", now.boot)
                            .putBoolean("commitment_hide_countdown", false).putLong("commitment_range_min", duration).putLong("commitment_range_max", duration);
                    stats.putString("active_session_identity", statistics).putLong("active_session_start_ms", now.wall);
                } else pause.putString("paid_pause_session_identity", identity).putLong("paid_pause_active_until", ends)
                        .putBoolean("paid_pause_rearm", false).putBoolean(PaidPauseManager.KEY_ENABLED, true);
                assertTrue(pause.commit()); assertTrue(stats.commit()); assertTrue(main.commit()); RuntimeSessionIdentity.changed();
                SessionExpiryAlarms.update(context, kind, "", identity, kind == SessionExpiryAlarms.Kind.PACT ? now.elapsed + duration : ends);
            }
            SessionExpiryAlarms.whenIdle().get(5, TimeUnit.SECONDS);
            saved.put("phase", "SEEDED"); write(file, saved);
            android.os.Bundle status = new android.os.Bundle(); status.putString("boot_fixture", "seeded"); status.putString("boot_kind", kindName);
            status.putString("boot_identity", identity); status.putLong("boot_ends_at", ends); status.putInt("boot_counter", now.boot);
            status.putInt("boot_pid", android.os.Process.myPid()); InstrumentationRegistry.getInstrumentation().sendStatus(42, status);
            complete = true;
        } finally { if (!complete) restoreFixture(file, saved); }
    }
    @Test public void recoverColdBootTimerAndRestoreTheOriginalGuestState() throws Exception {
        AtomicFile file = backup(); JSONObject saved = read(file);
        try {
            assertEquals("SEEDED", saved.getString("phase")); RemoteSessionClock.Sample now = RemoteSessionClock.observe(context);
            assertNotEquals(saved.getInt("pid"), android.os.Process.myPid()); assertTrue(now.boot > saved.getInt("boot"));
            SessionExpiryAlarms.Kind kind = SessionExpiryAlarms.Kind.valueOf(saved.getString("kind"));
            assertFalse(ScreenCaptureService.isRunning()); assertFalse(ScreenshotAccessibilityService.isRunning());
            assertEquals(saved.getString("identity"), (kind == SessionExpiryAlarms.Kind.PACT ? main() : pause())
                    .getString(kind == SessionExpiryAlarms.Kind.PACT ? "commitment_session_identity" : "paid_pause_session_identity", ""));
            assertEquals(saved.getLong("endsAt"), (kind == SessionExpiryAlarms.Kind.PACT ? main() : pause())
                    .getLong(kind == SessionExpiryAlarms.Kind.PACT ? "commitment_ends_at" : "paid_pause_active_until", 0));
            assertEquals(saved.getString("statsIdentity"), stats().getString("active_session_identity", ""));
            assertNotNull("Actual cold boot must install the scoped token before the observer starts",
                    PendingIntent.getBroadcast(context, kind == SessionExpiryAlarms.Kind.PACT ? 8317 : 8349,
                            SessionExpiryAlarms.intent(context, kind, saved.getString("identity")), PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE));
            android.os.Bundle status = new android.os.Bundle(); status.putString("boot_fixture", "recovered"); status.putInt("boot_counter", now.boot);
            InstrumentationRegistry.getInstrumentation().sendStatus(42, status);
        } finally { restoreFixture(file, saved); }
    }
}
