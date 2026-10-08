package com.subhub.app.capture.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Decodes every source frame and retains its timestamp; never samples via getFrameAtTime. */
public final class VideoExporter {
    public interface FrameFilter { Bitmap apply(Bitmap frame, int index, long timeUs) throws Exception; }
    public interface Progress { void update(long timeUs, long durationUs); }
    private VideoExporter() { }

    public static void export(Context context, Uri source, File destination, ExportOptions options,
            FrameFilter filter, BooleanSupplier cancelled, Progress progress) throws Exception {
        MediaExtractor input = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null; CodecInputSurface surface = null;
        boolean decoderStarted = false, encoderStarted = false, success = false;
        Drain drain = null;
        try {
            input.setDataSource(context, source, null);
            int videoTrack = track(input, "video/");
            if (videoTrack < 0) throw new IOException("No video track");
            MediaFormat format = input.getTrackFormat(videoTrack);
            int transfer = integer(format, MediaFormat.KEY_COLOR_TRANSFER, 0);
            int standard = integer(format, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                    || standard == MediaFormat.COLOR_STANDARD_BT2020)
                throw new IOException("HDR / BT.2020 video is not supported; choose an SDR source");
            int rotation = Math.floorMod(integer(format, MediaFormat.KEY_ROTATION, 0), 360);
            if (rotation % 90 != 0) throw new IOException("Unsupported video orientation");
            int width = format.getInteger(MediaFormat.KEY_WIDTH), height = format.getInteger(MediaFormat.KEY_HEIGHT);
            int[] size = options.dimensions(rotation % 180 == 0 ? width : height,
                    rotation % 180 == 0 ? height : width);
            long duration = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            input.selectTrack(videoTrack);
            long origin = Math.max(0, input.getSampleTime());
            muxer = new MediaMuxer(destination.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(0);
            int audioOutputTrack = -1;
            if (!options.mute) {
                audio.setDataSource(context, source, null);
                int audioTrack = track(audio, "audio/");
                if (audioTrack >= 0) {
                    MediaFormat audioFormat = audio.getTrackFormat(audioTrack);
                    if (!"audio/mp4a-latm".equals(audioFormat.getString(MediaFormat.KEY_MIME)))
                        throw new IOException("Audio codec cannot be preserved in MP4; enable Remove audio or choose AAC audio");
                    audio.selectTrack(audioTrack); origin = Math.min(origin, Math.max(0, audio.getSampleTime()));
                    audioOutputTrack = muxer.addTrack(audioFormat);
                }
            }
            MediaFormat output = MediaFormat.createVideoFormat("video/avc", size[0], size[1]);
            int fps = Math.max(1, Math.min(120, integer(format, MediaFormat.KEY_FRAME_RATE, 30)));
            output.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            output.setInteger(MediaFormat.KEY_BIT_RATE, options.quality.bitrate(size[0], size[1], fps));
            output.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            output.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            if (Build.VERSION.SDK_INT >= 29) output.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            output.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            output.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            output.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(output, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = new CodecInputSurface(encoder.createInputSurface());
            encoder.start(); encoderStarted = true;
            drain = new Drain(encoder, muxer, cancelled);
            format.setInteger(MediaFormat.KEY_ROTATION, 0);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0); decoder.start(); decoderStarted = true;
            boolean inputEnded = false, decodedEnded = false;
            int frame = 0; long lastProgress = SystemClock.elapsedRealtime();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!decodedEnded) {
                checkCancelled(cancelled);
                if (!inputEnded) {
                    int slot = decoder.dequeueInputBuffer(10_000);
                    if (slot >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(slot);
                        if (buffer == null) throw new IOException("Decoder input unavailable");
                        int bytes = input.readSampleData(buffer, 0);
                        if (bytes < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            if ((input.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                                throw new IOException("Encrypted media is not supported");
                            decoder.queueInputBuffer(slot, 0, bytes, input.getSampleTime(), 0);
                            input.advance();
                        }
                        lastProgress = SystemClock.elapsedRealtime();
                    }
                }
                int slot = decoder.dequeueOutputBuffer(info, 10_000);
                if (slot >= 0) {
                    try {
                        if (info.size > 0) {
                            Bitmap decoded;
                            try (Image image = decoder.getOutputImage(slot)) {
                                if (image == null) throw new IOException("Decoder cannot expose YUV frames on this device");
                                decoded = bitmap(image, decoder.getOutputFormat());
                            }
                            Bitmap oriented = decoded, scaled = null, rendered = null;
                            try {
                                if (rotation != 0) {
                                    Matrix matrix = new Matrix(); matrix.postRotate(rotation);
                                    oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
                                }
                                scaled = Bitmap.createScaledBitmap(oriented, size[0], size[1], true);
                                rendered = filter.apply(scaled, frame++, info.presentationTimeUs - origin);
                                if (rendered == null) throw new IOException("Frame rendering failed");
                                drain.available(false);
                                surface.draw(rendered, Math.max(0, info.presentationTimeUs - origin));
                                drain.available(false);
                                progress.update(Math.max(0, info.presentationTimeUs - origin), duration);
                            } finally {
                                if (rendered != null && rendered != scaled) rendered.recycle();
                                if (scaled != null && scaled != oriented) scaled.recycle();
                                if (oriented != decoded) oriented.recycle();
                                decoded.recycle();
                            }
                        }
                        decodedEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally { decoder.releaseOutputBuffer(slot, false); }
                    lastProgress = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - lastProgress > 30_000) {
                    throw new IOException("Video decoder timed out");
                }
            }
            if (frame == 0) throw new IOException("No video frames decoded");
            encoder.signalEndOfInputStream(); drain.available(true);
            if (!drain.started || drain.samples == 0) throw new IOException("Video encoder produced no frames");
            if (audioOutputTrack >= 0) copyAudio(audio, muxer, audioOutputTrack, origin, cancelled);
            muxer.stop(); drain.started = false;
            success = true;
        } finally {
            if (decoder != null) { if (decoderStarted) try { decoder.stop(); } catch (RuntimeException ignored) { } decoder.release(); }
            if (surface != null) surface.close();
            if (encoder != null) { if (encoderStarted) try { encoder.stop(); } catch (RuntimeException ignored) { } encoder.release(); }
            if (muxer != null) { if (drain != null && drain.started) try { muxer.stop(); } catch (RuntimeException ignored) { } muxer.release(); }
            input.release(); audio.release();
            if (!success && destination.exists() && !destination.delete()) destination.deleteOnExit();
        }
    }
    private static int track(MediaExtractor extractor, String prefix) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return index;
        }
        return -1;
    }
    private static int integer(MediaFormat format, String key, int fallback) {
        if (!format.containsKey(key)) return fallback;
        try { return format.getInteger(key); }
        catch (ClassCastException ignored) { return Math.round(format.getFloat(key)); }
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private static void copyAudio(MediaExtractor audio, MediaMuxer muxer, int track, long origin,
            BooleanSupplier cancelled) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (audio.getSampleTime() >= 0) {
            checkCancelled(cancelled);
            if ((audio.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw new IOException("Encrypted audio is not supported");
            buffer.clear(); int length = audio.readSampleData(buffer, 0);
            if (length < 0) break;
            info.set(0, length, Math.max(0, audio.getSampleTime() - origin),
                    (audio.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buffer, info); audio.advance();
        }
    }
    private static Bitmap bitmap(Image image, MediaFormat format) throws IOException {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IOException("Unsupported decoder pixel format");
        Rect crop = image.getCropRect(); int width = crop.width(), height = crop.height();
        int[] pixels = new int[width * height];
        boolean full = integer(format, MediaFormat.KEY_COLOR_RANGE, 0) == MediaFormat.COLOR_RANGE_FULL;
        boolean bt709 = integer(format, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                == MediaFormat.COLOR_STANDARD_BT709;
        ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int x = col + crop.left, cy = row + crop.top;
            float luma = (y.get(y.position() + cy * planes[0].getRowStride() + x * planes[0].getPixelStride()) & 255);
            float blue = (u.get(u.position() + (cy / 2) * planes[1].getRowStride() + (x / 2) * planes[1].getPixelStride()) & 255) - 128;
            float red = (v.get(v.position() + (cy / 2) * planes[2].getRowStride() + (x / 2) * planes[2].getPixelStride()) & 255) - 128;
            if (!full) { luma = (luma - 16) * (255f / 219); blue *= 255f / 224; red *= 255f / 224; }
            int r = clamp(luma + (bt709 ? 1.5748f : 1.402f) * red);
            int g = clamp(luma - (bt709 ? .187324f : .344136f) * blue - (bt709 ? .468124f : .714136f) * red);
            int b = clamp(luma + (bt709 ? 1.8556f : 1.772f) * blue);
            pixels[row * width + col] = 0xff000000 | r << 16 | g << 8 | b;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }
    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static final class Drain {
        final MediaCodec codec; final MediaMuxer muxer; final BooleanSupplier cancelled;
        boolean started; int track; int samples;
        Drain(MediaCodec codec, MediaMuxer muxer, BooleanSupplier cancelled) {
            this.codec = codec; this.muxer = muxer; this.cancelled = cancelled;
        }
        void available(boolean untilEnd) throws IOException {
            long last = SystemClock.elapsedRealtime(); MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                checkCancelled(cancelled);
                int slot = codec.dequeueOutputBuffer(info, untilEnd ? 10_000 : 0);
                if (slot == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEnd) return;
                    if (SystemClock.elapsedRealtime() - last > 30_000) throw new IOException("Video encoder timed out");
                } else if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (started) throw new IOException("Encoder changed format during export");
                    track = muxer.addTrack(codec.getOutputFormat()); muxer.start(); started = true;
                } else if (slot >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            ByteBuffer bytes = codec.getOutputBuffer(slot);
                            if (!started || bytes == null) throw new IOException("Encoder output unavailable");
                            bytes.position(info.offset); bytes.limit(info.offset + info.size);
                            muxer.writeSampleData(track, bytes, info); samples++;
                        }
                    } finally { codec.releaseOutputBuffer(slot, false); }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                    last = SystemClock.elapsedRealtime();
                }
            }
        }
    }
}
