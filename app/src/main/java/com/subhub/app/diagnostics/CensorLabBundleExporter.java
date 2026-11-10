package com.subhub.app.diagnostics;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds one portable Lab bundle with an optional app-owned screen recording. */
public final class CensorLabBundleExporter {
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long MAX_VIDEO_BYTES = 256L * 1024L * 1024L;

    private CensorLabBundleExporter() {}

    public static File export(Context context, CensorLabRecorder.CompletedSession session,
            boolean includeVideo) throws IOException {
        if (session == null || !session.manifest.isFile() || !session.trace.isFile()) {
            throw new IOException("No completed Censor Lab session is available");
        }
        File video = includeVideo ? trustedVideo(session) : null;
        File outputDirectory = new File(context.getCacheDir(), "censor-lab/exports");
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory()) {
            throw new IOException("Could not create the diagnostics export directory");
        }
        pruneExports(outputDirectory);
        File output = new File(outputDirectory, "subhub-censor-lab-" + session.id + '-'
                + System.currentTimeMillis() + ".zip");
        File partial = new File(outputDirectory, output.getName() + ".partial");
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Could not clear the previous diagnostics export");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(partial), COPY_BUFFER))) {
            zip.setLevel(Deflater.BEST_SPEED);
            JSONObject manifest;
            byte[] manifestBytes;
            try {
                manifest = new JSONObject(readUtf8(session.manifest));
                manifest.put("videoAttached", video != null);
                JSONObject privacy = manifest.optJSONObject("privacy");
                if (privacy == null) privacy = new JSONObject();
                privacy.put("pixelCapture", video != null);
                manifest.put("privacy", privacy);
                JSONObject recording = manifest.optJSONObject("recording");
                if (recording == null) recording = new JSONObject();
                boolean sessionUsedEncoder = recording.optBoolean(
                        "sessionUsedScreenEncoder",
                        recording.optBoolean("inAppScreenRecording", false));
                recording.put("inAppScreenRecording", video != null);
                recording.put("sessionUsedScreenEncoder", sessionUsedEncoder);
                recording.put("videoKind", video == null
                        ? "none" : "mediaprojection-display");
                if (video == null) {
                    recording.put("width", 0);
                    recording.put("height", 0);
                    recording.put("frameRate", 0);
                    recording.put("bitRate", 0);
                    recording.put("startedElapsedNanos", 0L);
                    recording.put("stoppedElapsedNanos", 0L);
                    recording.put("bytes", 0L);
                    recording.put("stopReason", "omitted-from-bundle");
                    recording.put("measurementOverhead", sessionUsedEncoder
                            ? "hardware screen encoder active; video omitted" : "none");
                }
                manifest.put("recording", recording);
                manifestBytes = (manifest.toString(2) + "\n")
                        .getBytes(StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new IOException("Could not read the diagnostics manifest", error);
            }
            addBytes(zip, "manifest.json", manifestBytes);
            addFile(zip, "trace.ndjson", session.trace);
            addBytes(zip, "README.txt", (
                    "SubHub Censor Lab bundle\n"
                    + "trace.ndjson contains sanitized timing and geometry telemetry only.\n"
                    + "screen-recording.mp4 is present only after explicit Android screen-capture consent.\n"
                    + "The recording is video-only; SubHub does not capture microphone audio.\n"
                    + "Use manifest elapsed timestamps and CensorLab SYNC markers to align video.\n")
                    .getBytes(StandardCharsets.UTF_8));
            if (video != null) {
                // MP4 is already compressed. Deflate level zero avoids wasting CPU and delaying
                // the ready-to-share bundle while retaining a streaming ZIP entry.
                zip.setLevel(Deflater.NO_COMPRESSION);
                addFile(zip, "screen-recording.mp4", video);
            }
        } catch (IOException | RuntimeException error) {
            partial.delete();
            output.delete();
            throw error;
        }
        if (!partial.renameTo(output)) {
            partial.delete();
            throw new IOException("Could not finalize the diagnostics export");
        }
        return output;
    }

    private static File trustedVideo(CensorLabRecorder.CompletedSession session)
            throws IOException {
        File video = session.video;
        if (video == null || !video.isFile() || video.length() <= 0L) return null;
        if (video.length() > MAX_VIDEO_BYTES) {
            throw new IOException("The Censor Lab recording exceeded its size limit");
        }
        String root = session.directory.getCanonicalPath() + File.separator;
        if (!video.getCanonicalPath().startsWith(root)) {
            throw new IOException("The Censor Lab recording is outside its session");
        }
        return video;
    }

    private static void pruneExports(File directory) {
        File[] partials = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("subhub-censor-lab-")
                && file.getName().endsWith(".partial"));
        if (partials != null) {
            for (File partial : partials) partial.delete();
        }
        File[] exports = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("subhub-censor-lab-")
                && file.getName().endsWith(".zip"));
        if (exports == null || exports.length < 3) return;
        Arrays.sort(exports, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = 2; index < exports.length; index++) exports[index].delete();
    }

    private static void addFile(ZipOutputStream zip, String name, File file) throws IOException {
        try (InputStream stream = new BufferedInputStream(
                new FileInputStream(file), COPY_BUFFER)) {
            addStream(zip, name, stream);
        }
    }

    private static String readUtf8(File input) throws IOException {
        try (FileInputStream stream = new FileInputStream(input);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) bytes.write(buffer, 0, read);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void addBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void addStream(ZipOutputStream zip, String name, InputStream stream)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        byte[] buffer = new byte[COPY_BUFFER];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            if (read > 0) zip.write(buffer, 0, read);
        }
        zip.closeEntry();
    }
}
