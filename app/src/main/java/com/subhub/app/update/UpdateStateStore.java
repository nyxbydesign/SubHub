package com.subhub.app.update;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

import java.io.File;
import java.util.Collections;
import java.util.List;

/** Durable updater state; contains no credentials. */
public final class UpdateStateStore {
    private static final String PREFS = "subhub_updates";
    private static final String AUTO = "automatic_checks";
    private static final String DEV = "dev_updates";
    private static final String CHANNEL_REVISION = "channel_revision";
    private static final String LAST_CHECK = "last_check";
    private static final String ETAG = "release_etag";
    private static final String CANDIDATE = "candidate";
    private static final String RELEASE_HISTORY = "release_history";
    private static final String LAST_NOTIFIED = "last_notified";
    private static final String DOWNLOAD_ID = "download_id";
    private static final String AUTOMATIC_DOWNLOAD_ATTEMPTS = "automatic_download_attempts";
    private static final String VERIFIED_PATH = "verified_path";
    private static final String VERIFIED_AT = "verified_at";
    private static final String INSTALL_REQUESTED = "install_requested";
    private final Context context;
    private final SharedPreferences preferences;

    public UpdateStateStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.contains(DEV)) {
            // Old versions included previews implicitly; force a fresh, stable-only lookup.
            preferences.edit().putBoolean(DEV, false).remove(ETAG).apply();
        }
    }

    public boolean automaticChecks() { return preferences.getBoolean(AUTO, true); }
    public void setAutomaticChecks(boolean enabled) {
        synchronized (UpdateStateStore.class) {
            preferences.edit().putBoolean(AUTO, enabled).apply();
            if (!enabled) new UpdateDownloadCoordinator(context).cancel();
        }
    }
    public boolean devUpdates() { return preferences.getBoolean(DEV, false); }
    public long channelRevision() { return preferences.getLong(CHANNEL_REVISION, 0L); }

    public void setDevUpdates(boolean enabled) {
        synchronized (UpdateStateStore.class) {
            if (enabled == devUpdates()) return;
            new UpdateDownloadCoordinator(context).cancel();
            preferences.edit().putBoolean(DEV, enabled)
                    .putLong(CHANNEL_REVISION, channelRevision() + 1L)
                    .remove(ETAG).remove(CANDIDATE).remove(LAST_NOTIFIED).remove(LAST_CHECK).apply();
        }
    }

    /** A check started under another channel must never repopulate its old candidate. */
    boolean saveCheck(long revision, String etag, List<ReleaseHistoryItem> history,
            UpdateCandidate candidate) {
        synchronized (UpdateStateStore.class) {
            if (revision != channelRevision()) return false;
            setEtag(etag);
            setLastCheck(System.currentTimeMillis());
            setReleaseHistory(history);
            if (candidate != null) setCandidate(candidate);
            else {
                clearCandidate();
                new UpdateDownloadCoordinator(context).cancel();
            }
            return true;
        }
    }
    public long lastCheck() { return preferences.getLong(LAST_CHECK, 0L); }
    public void setLastCheck(long value) { preferences.edit().putLong(LAST_CHECK, value).apply(); }
    public String etag() { return preferences.getString(ETAG, ""); }
    public void setEtag(String value) { preferences.edit().putString(ETAG, value == null ? "" : value).apply(); }
    public long downloadId() { return preferences.getLong(DOWNLOAD_ID, -1L); }
    public void setDownloadId(long id) { preferences.edit().putLong(DOWNLOAD_ID, id).apply(); }

    /** The attempt survives process recreation and download-row cleanup. */
    boolean reserveAutomaticDownload(UpdateManifest.Asset asset, long now) {
        synchronized (UpdateStateStore.class) {
            try {
                String next = UpdateDownloadAttempts.reserve(
                        preferences.getString(AUTOMATIC_DOWNLOAD_ATTEMPTS, "[]"), asset.sha256, now);
                return next != null && preferences.edit().putString(AUTOMATIC_DOWNLOAD_ATTEMPTS, next).commit();
            } catch (JSONException malformed) {
                return false;
            }
        }
    }
    public String verifiedPath() {
        synchronized (UpdateStateStore.class) {
            return preferences.getString(VERIFIED_PATH, "");
        }
    }
    public void setVerifiedPath(String path) {
        preferences.edit().putString(VERIFIED_PATH, path)
                .putLong(VERIFIED_AT, path == null || path.isEmpty() ? 0L : System.currentTimeMillis()).apply();
    }
    /** Ready notices must follow a successful durable publication of the verified private file. */
    boolean publishVerified(String path) {
        synchronized (UpdateStateStore.class) {
            boolean persisted = preferences.edit().putString(VERIFIED_PATH, path)
                    .putLong(VERIFIED_AT, System.currentTimeMillis()).remove(INSTALL_REQUESTED).commit();
            if (!persisted) {
                // A failed commit can still publish its values in memory. Invalidate them before
                // readers or a concurrent preparation can mistake the file for acknowledged state.
                preferences.edit().remove(VERIFIED_PATH).remove(VERIFIED_AT)
                        .remove(INSTALL_REQUESTED).apply();
                new File(path).delete();
            }
            return persisted;
        }
    }

    public boolean installRequested() {
        return preferences.getBoolean(INSTALL_REQUESTED, false);
    }
    public void setInstallRequested(boolean requested) {
        preferences.edit().putBoolean(INSTALL_REQUESTED, requested).apply();
    }

    public UpdateCandidate candidate() {
        String value = preferences.getString(CANDIDATE, "");
        if (value == null || value.isEmpty()) return null;
        try {
            UpdateCandidate candidate = UpdateCandidate.parse(value);
            return candidate.prerelease && !devUpdates() ? null : candidate;
        }
        catch (JSONException | IllegalArgumentException exception) { return null; }
    }

    static boolean sameCandidate(UpdateCandidate first, UpdateCandidate second) {
        if (first == null || second == null || first.prerelease != second.prerelease) return false;
        try { return first.manifest.json().equals(second.manifest.json()); }
        catch (JSONException invalid) { return false; }
    }

    public void setCandidate(UpdateCandidate candidate) {
        synchronized (UpdateStateStore.class) {
            if (!sameCandidate(candidate(), candidate)) {
                new UpdateDownloadCoordinator(context).cancel();
                preferences.edit().remove(LAST_NOTIFIED).apply();
            }
            try { preferences.edit().putString(CANDIDATE, candidate.json()).apply(); }
            catch (JSONException ignored) { }
        }
    }

    public void clearCandidate() {
        preferences.edit().remove(CANDIDATE).remove(LAST_NOTIFIED).apply();
    }

    public List<ReleaseHistoryItem> releaseHistory() {
        String value = preferences.getString(RELEASE_HISTORY, "");
        try { return ReleaseHistoryItem.decode(value); }
        catch (JSONException exception) { return Collections.emptyList(); }
    }

    public void setReleaseHistory(List<ReleaseHistoryItem> history) {
        try {
            preferences.edit().putString(RELEASE_HISTORY, ReleaseHistoryItem.encode(history)).apply();
        } catch (JSONException ignored) { }
    }

    boolean wasNotified(String version) {
        return version.equals(preferences.getString(LAST_NOTIFIED, ""));
    }

    public boolean markNotified(String version) {
        if (wasNotified(version)) return false;
        preferences.edit().putString(LAST_NOTIFIED, version).apply();
        return true;
    }

    public void clearDownload(boolean deleteFile) {
        synchronized (UpdateStateStore.class) {
            UpdateNotifications.cancelDownload(context, downloadId());
            UpdateNotifications.cancelReady(context);
            if (deleteFile) {
                String path = verifiedPath();
                if (!path.isEmpty()) new File(path).delete();
            }
            preferences.edit().remove(DOWNLOAD_ID).remove(VERIFIED_PATH).remove(VERIFIED_AT)
                    .remove(INSTALL_REQUESTED).apply();
        }
    }

    public void cleanupInstalled(long installedVersionCode) {
        UpdateCandidate current = candidate();
        if (current != null && installedVersionCode >= current.manifest.versionCode) {
            clearDownload(true);
            preferences.edit().remove(CANDIDATE).remove(LAST_NOTIFIED).apply();
        }
        long verifiedAt = preferences.getLong(VERIFIED_AT, 0L);
        if (verifiedAt > 0 && System.currentTimeMillis() - verifiedAt > 7L * 24L * 60L * 60L * 1000L) {
            clearDownload(true);
        }
    }

    public File updateDirectory() {
        File directory = new File(context.getFilesDir(), "updates");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }
}
