package com.subhub.app.update;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

/** Starts and restores the single compatible APK download. */
public final class UpdateDownloadCoordinator {
    public static final class Status {
        public final int state;
        public final long downloaded;
        public final long total;
        Status(int state, long downloaded, long total) {
            this.state = state;
            this.downloaded = downloaded;
            this.total = total;
        }
    }

    private final Context context;
    private final DownloadManager downloads;
    private final UpdateStateStore state;

    public UpdateDownloadCoordinator(Context context) {
        this.context = context.getApplicationContext();
        downloads = this.context.getSystemService(DownloadManager.class);
        state = new UpdateStateStore(this.context);
    }

    public long start(UpdateCandidate candidate) {
        return start(candidate, true);
    }

    /** Called off the UI thread after a successful check; does not open the installer. */
    public void prepare(UpdateCandidate candidate, boolean automatic) {
        long id;
        try { id = start(candidate, !automatic); }
        catch (RuntimeException unavailable) { return; }
        if (id >= 0) {
            Status status = status();
            if (status != null && status.state == DownloadManager.STATUS_SUCCESSFUL) {
                UpdateDownloadFinalizer.finish(context, id);
            }
        } else {
            UpdateNotifications.ready(context, candidate);
        }
    }

    private long start(UpdateCandidate candidate, boolean userRequested) {
        synchronized (UpdateStateStore.class) {
            if ((!userRequested && !state.automaticChecks())
                    || !UpdateStateStore.sameCandidate(candidate, state.candidate())
                    || (candidate.prerelease && !state.devUpdates())
                    || !candidate.manifest.isCompatible(com.subhub.app.BuildConfig.VERSION_CODE)) {
                throw new IllegalStateException("Update is no longer selected");
            }
            UpdateManifest.Asset asset = candidate.manifest.selectAsset(android.os.Build.SUPPORTED_ABIS);
            if (asset == null) throw new IllegalStateException("No compatible APK");
            String ready = state.verifiedPath();
            if (!ready.isEmpty() && new File(ready).isFile()) {
                // Recover a process exit after private-file publication but before system-row cleanup.
                long completed = state.downloadId();
                if (completed >= 0) downloads.remove(completed);
                state.setDownloadId(-1L);
                return -1L;
            }
            Status active = status();
            if (active != null && active.state != DownloadManager.STATUS_FAILED) return state.downloadId();
            cancel();
            File destination = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), asset.name);
            if (destination.exists() && !destination.delete()) throw new IllegalStateException("Old download unavailable");
            long id = downloads.enqueue(request(candidate, asset));
            state.setDownloadId(id);
            state.setInstallRequested(false);
            return id;
        }
    }

    DownloadManager.Request request(UpdateCandidate candidate, UpdateManifest.Asset asset) {
        return new DownloadManager.Request(Uri.parse(asset.url))
                .setTitle(context.getString(com.subhub.app.R.string.update_download_title,
                        candidate.manifest.versionName))
                .setDescription(context.getString(com.subhub.app.R.string.update_download_body))
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(UpdateNotifications.downloadVisibility(context))
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, asset.name);
    }

    public void cancel() {
        synchronized (UpdateStateStore.class) {
            long id = state.downloadId();
            if (id >= 0) downloads.remove(id);
            state.clearDownload(true);
        }
    }

    public Status status() {
        long id = state.downloadId();
        if (id < 0) return null;
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return new Status(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
        }
    }
}
