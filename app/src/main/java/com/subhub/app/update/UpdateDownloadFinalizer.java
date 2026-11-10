package com.subhub.app.update;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Publishes only verified bytes belonging to the current download and channel. */
final class UpdateDownloadFinalizer {
    enum Result { READY, FAILED }

    private UpdateDownloadFinalizer() {}

    static synchronized Result finish(Context sourceContext, long id) {
        Context context = sourceContext.getApplicationContext();
        UpdateStateStore state = new UpdateStateStore(context);
        final UpdateCandidate candidate;
        final long revision;
        synchronized (UpdateStateStore.class) {
            // A late broadcast cannot return READY for another download's file.
            if (id < 0 || state.downloadId() != id) return Result.FAILED;
            candidate = state.candidate();
            revision = state.channelRevision();
            String ready = state.verifiedPath();
            if (candidate != null && !ready.isEmpty() && new File(ready).isFile()) {
                context.getSystemService(DownloadManager.class).remove(id);
                state.setDownloadId(-1L);
                UpdateNotifications.ready(context, candidate);
                return Result.READY;
            }
        }
        UpdateManifest.Asset asset = candidate == null ? null
                : candidate.manifest.selectAsset(android.os.Build.SUPPORTED_ABIS);
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        Uri source = manager == null ? null : manager.getUriForDownloadedFile(id);
        if (asset == null || source == null) {
            failIfCurrent(context, state, id);
            return Result.FAILED;
        }
        // The ID isolates concurrent generations even when the release reuses an asset name.
        File destination = new File(state.updateDirectory(), "download-" + id + ".apk");
        boolean published = false;
        try {
            try (InputStream raw = context.getContentResolver().openInputStream(source);
                    BufferedInputStream input = new BufferedInputStream(raw);
                    BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    copied += count;
                    if (copied > asset.size) throw new java.io.IOException("APK exceeds manifest size");
                    output.write(buffer, 0, count);
                }
            }
            if (!UpdateVerifier.verify(context, destination, candidate.manifest, asset).succeeded()) {
                throw new java.io.IOException("APK verification failed");
            }
            synchronized (UpdateStateStore.class) {
                if (!isCurrent(state, id, revision, candidate)) {
                    destination.delete();
                    return Result.FAILED;
                }
                if (!state.publishVerified(destination.getAbsolutePath())) {
                    throw new java.io.IOException("Verified update state was not persisted");
                }
                published = true;
                manager.remove(id);
                state.setDownloadId(-1L);
                UpdateNotifications.cancelDownload(context, id);
                UpdateNotifications.ready(context, candidate);
            }
            return Result.READY;
        } catch (Exception exception) {
            // A notification/provider failure cannot invalidate an already durable verified APK.
            if (published) return Result.READY;
            destination.delete();
            failIfCurrent(context, state, id);
            return Result.FAILED;
        } finally {
            manager.remove(id);
        }
    }

    static boolean isCurrent(UpdateStateStore state, long id, long revision, UpdateCandidate candidate) {
        return id >= 0 && state.downloadId() == id && revision == state.channelRevision()
                && UpdateStateStore.sameCandidate(candidate, state.candidate());
    }

    private static void failIfCurrent(Context context, UpdateStateStore state, long id) {
        synchronized (UpdateStateStore.class) {
            if (state.downloadId() == id) new UpdateDownloadCoordinator(context).cancel();
        }
    }
}
