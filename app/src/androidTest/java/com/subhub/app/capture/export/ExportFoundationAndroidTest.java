package com.subhub.app.capture.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ExportFoundationAndroidTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void snapshotExcludesCredentialsAndIsImmutable() throws Exception {
        Map<String, Object> original = new HashMap<>();
        original.put("censor_type", "blur"); original.put("enabled_categories", new HashSet<>(Arrays.asList("FACE_FEMALE")));
        original.put("controller_pin_hash", "synthetic-not-a-credential");
        original.put("paypal_client_secret", "synthetic-not-a-credential");
        SharedPreferences copy = ExportSettings.frozen(ExportSettings.encode(original));
        original.put("censor_type", "box");
        assertEquals("blur", copy.getString("censor_type", ""));
        assertFalse(copy.contains("controller_pin_hash")); assertFalse(copy.contains("paypal_client_secret"));
        try { copy.edit(); fail("Snapshot was editable"); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void recoveryRetainsCompletedItemsAndRetriesOnlyUnfinished() throws Exception {
        try (ExportJobStore store = new ExportJobStore(context)) {
            String job = store.create(Arrays.asList(Uri.parse("content://synthetic/one"), Uri.parse("content://synthetic/two")),
                    ExportSettings.encode(Collections.emptyMap()), ExportOptions.defaults());
            List<ExportJobStore.Item> items = store.items(job);
            store.update(items.get(0).id, ExportJobStore.State.SAVED, Uri.parse("content://synthetic/saved"), "Saved", 100);
            store.update(items.get(1).id, ExportJobStore.State.RUNNING, null, "", 20);
            store.recover();
            assertEquals(ExportJobStore.State.SAVED, store.items(job).get(0).state);
            assertEquals(ExportJobStore.State.INTERRUPTED, store.items(job).get(1).state);
            store.retry(job);
            assertEquals(ExportJobStore.State.SAVED, store.items(job).get(0).state);
            assertEquals(ExportJobStore.State.PENDING, store.items(job).get(1).state);
        }
    }
    @Test public void videoPreservesVariableTimestampsRotationAudioAndRendersEveryFrame() throws Exception {
        File source = new File(context.getFilesDir(), "export-test-source.mp4");
        long[] times = {0, 33_333, 90_000, 150_000, 250_000, 330_000, 400_000, 500_000};
        generate(source, times, 90);
        File output = new File(context.getFilesDir(), "export-test-result.mp4");
        List<Long> calls = new ArrayList<>();
        VideoExporter.export(context, Uri.fromFile(source), output, ExportOptions.defaults(), (frame, index, time) -> {
            assertEquals(48, frame.getWidth()); assertEquals(64, frame.getHeight());
            calls.add(time); Bitmap result = frame.copy(Bitmap.Config.ARGB_8888, true);
            Canvas canvas = new Canvas(result); Paint paint = new Paint(); paint.setColor(Color.MAGENTA);
            canvas.drawRect(8, 8, 40, 56, paint); return result;
        }, () -> false, (time, duration) -> { });
        assertEquals(times.length, calls.size());
        assertArrayEquals(times, timestamps(output, "video/"));
        assertArrayEquals(timestamps(source, "audio/"), timestamps(output, "audio/"));
        ExportMedia.validate(output, true);
        try (MediaMetadataRetriever reader = new MediaMetadataRetriever()) {
            reader.setDataSource(output.getAbsolutePath()); Bitmap frame = reader.getFrameAtTime(0);
            assertNotNull(frame); int color = frame.getPixel(24, 32);
            assertTrue(Color.red(color) > 180); assertTrue(Color.blue(color) > 180); assertTrue(Color.green(color) < 80);
            frame.recycle();
        }
    }
    @Test public void muteRemovesAudioAndCancellationLeavesNoPartialVideo() throws Exception {
        File source = new File(context.getFilesDir(), "export-mute-source.mp4");
        generate(source, new long[] {0, 100_000, 200_000}, 0);
        File result = new File(context.getFilesDir(), "export-mute-result.mp4");
        VideoExporter.export(context, Uri.fromFile(source), result,
                new ExportOptions(ExportOptions.Quality.DRAFT, 1, true, false), (frame, index, time) -> frame,
                () -> false, (time, duration) -> { });
        assertEquals(0, timestamps(result, "audio/").length);
        File partial = new File(context.getFilesDir(), "export-cancelled.mp4");
        try {
            VideoExporter.export(context, Uri.fromFile(source), partial, ExportOptions.defaults(),
                    (frame, index, time) -> frame, () -> true, (time, duration) -> { });
            fail("Cancelled export completed");
        } catch (CancellationException expected) { assertFalse(partial.exists()); }
    }
    private static long[] timestamps(File file, String prefix) throws Exception {
        MediaExtractor reader = new MediaExtractor(); List<Long> values = new ArrayList<>();
        try {
            reader.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < reader.getTrackCount(); i++) if (reader.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix)) {
                reader.selectTrack(i);
                while (reader.getSampleTime() >= 0) { values.add(reader.getSampleTime()); reader.advance(); }
                break;
            }
        } finally { reader.release(); }
        long[] result = new long[values.size()]; for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
    private static final class Sample {
        byte[] bytes; long time; int flags;
        Sample(byte[] bytes, long time, int flags) { this.bytes = bytes; this.time = time; this.flags = flags; }
    }
    static void generate(File file, long[] times, int rotation) throws Exception {
        MediaFormat video = MediaFormat.createVideoFormat("video/avc", 64, 48);
        video.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        video.setInteger(MediaFormat.KEY_BIT_RATE, 500_000); video.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        video.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
        List<Sample> videoSamples = new ArrayList<>(); MediaFormat[] videoFormat = new MediaFormat[1];
        try {
            encoder.configure(video, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            try (CodecInputSurface surface = new CodecInputSurface(encoder.createInputSurface())) {
                encoder.start(); Bitmap bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
                try {
                    for (int i = 0; i < times.length; i++) {
                        bitmap.eraseColor(i % 2 == 0 ? Color.GREEN : Color.BLUE); surface.draw(bitmap, times[i]);
                        drain(encoder, false, videoSamples, videoFormat);
                    }
                    encoder.signalEndOfInputStream(); drain(encoder, true, videoSamples, videoFormat);
                } finally { bitmap.recycle(); encoder.stop(); }
            }
        } finally { encoder.release(); }
        MediaFormat audio = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1);
        audio.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audio.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        MediaCodec audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        List<Sample> audioSamples = new ArrayList<>(); MediaFormat[] audioFormat = new MediaFormat[1];
        try {
            audioEncoder.configure(audio, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); audioEncoder.start();
            for (int i = 0; i <= 24; i++) {
                int slot = audioEncoder.dequeueInputBuffer(1_000_000); assertTrue(slot >= 0);
                ByteBuffer bytes = audioEncoder.getInputBuffer(slot); bytes.clear();
                if (i < 24) for (int j = 0; j < 1024; j++) {
                    short value = (short) (Math.sin((i * 1024 + j) * 2 * Math.PI * 440 / 44100) * 2000);
                    bytes.put((byte) value); bytes.put((byte) (value >> 8));
                }
                audioEncoder.queueInputBuffer(slot, 0, i == 24 ? 0 : 2048,
                        i * 1024L * 1_000_000 / 44100, i == 24 ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                drain(audioEncoder, i == 24, audioSamples, audioFormat);
            }
            audioEncoder.stop();
        } finally { audioEncoder.release(); }
        MediaMuxer muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            int vt = muxer.addTrack(videoFormat[0]); int at = muxer.addTrack(audioFormat[0]);
            muxer.setOrientationHint(rotation); muxer.start();
            for (Sample sample : videoSamples) write(muxer, vt, sample);
            for (Sample sample : audioSamples) write(muxer, at, sample);
            muxer.stop();
        } finally { muxer.release(); }
    }
    private static void write(MediaMuxer muxer, int track, Sample sample) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); info.set(0, sample.bytes.length, Math.max(0, sample.time), sample.flags);
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info);
    }
    private static void drain(MediaCodec codec, boolean untilEnd, List<Sample> samples, MediaFormat[] format) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            int slot = codec.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
            if (slot == MediaCodec.INFO_TRY_AGAIN_LATER && !untilEnd) return;
            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format[0] = codec.getOutputFormat();
            if (slot >= 0) {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(slot); buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size]; buffer.get(bytes); samples.add(new Sample(bytes, info.presentationTimeUs, info.flags));
                }
                codec.releaseOutputBuffer(slot, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
        fail("Fixture encoder timeout");
    }
}
