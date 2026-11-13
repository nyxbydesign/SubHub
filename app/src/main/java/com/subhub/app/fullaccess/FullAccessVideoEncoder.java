package com.subhub.app.fullaccess;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** Hardware AVC from the existing approved projection, without copying raw frames. */
final class FullAccessVideoEncoder extends MediaCodec.Callback implements AutoCloseable {
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    final FullAccessVideo.Size size;
    final Surface surface;
    private final MediaCodec codec;
    private final FullAccessVideo.Queue queue;
    private final BooleanSupplier visible;
    private final Runnable onError;
    private boolean closed;

    static FullAccessVideoEncoder open(int width, int height, Handler worker, FullAccessVideo.Queue queue,
            BooleanSupplier visible, Runnable onError) {
        if (Build.VERSION.SDK_INT < 29) return null;
        MediaCodecInfo[] available;
        try { available = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos(); }
        catch (RuntimeException unavailable) { return null; }
        for (int limit : new int[]{720, 420}) {
            for (MediaCodecInfo info : available) {
                if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
                try {
                    MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(MIME);
                    MediaCodecInfo.VideoCapabilities video = capabilities.getVideoCapabilities();
                    if (video == null) continue;
                    FullAccessVideo.Size size = FullAccessVideo.fit(width, height, limit,
                            video.getWidthAlignment(), video.getHeightAlignment());
                    if (!size.valid() || !video.areSizeAndRateSupported(size.width(), size.height(), FullAccessVideo.FRAME_RATE))
                        continue;
                    MediaCodecInfo.EncoderCapabilities encoder = capabilities.getEncoderCapabilities();
                    if (encoder == null || !encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                        continue;
                    int bitrate = video.getBitrateRange().clamp(limit == 720 ? 1_800_000 : 850_000);
                    if (bitrate > 1_800_000) continue;
                    return new FullAccessVideoEncoder(info.getName(), size, bitrate, worker, queue, visible, onError);
                } catch (IOException | RuntimeException unavailable) {
                    // Another advertised hardware encoder or the legacy JPEG path can still work.
                }
            }
        }
        return null;
    }

    @RequiresApi(29)
    private FullAccessVideoEncoder(String name, FullAccessVideo.Size size, int bitrate, Handler worker,
            FullAccessVideo.Queue queue, BooleanSupplier visible, Runnable onError) throws IOException {
        this.size = size;
        this.queue = queue;
        this.visible = visible;
        this.onError = onError;
        codec = MediaCodec.createByCodecName(name);
        Surface created = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, size.width(), size.height());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FullAccessVideo.FRAME_RATE);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FullAccessVideo.FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec.setCallback(this, worker);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            created = codec.createInputSurface();
            codec.start();
        } catch (RuntimeException failure) {
            if (created != null) created.release();
            codec.release();
            throw failure;
        }
        surface = created;
    }

    @Override public void onInputBufferAvailable(MediaCodec ignored, int index) { }

    @Override public void onOutputBufferAvailable(MediaCodec ignored, int index, MediaCodec.BufferInfo info) {
        if (closed) return;
        try {
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || !visible.getAsBoolean()) return;
            if (info.size <= 0) return;
            if (info.size > FullAccessVideo.MAX_FRAME_BYTES || (info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
                queue.hide();
                return;
            }
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer == null) return;
            byte[] bytes = new byte[info.size];
            buffer.position(info.offset).limit(info.offset + info.size);
            buffer.get(bytes);
            queue.publish(FullAccessVideo.Packet.sample((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                    Math.max(0, info.presentationTimeUs), bytes));
        } catch (RuntimeException failure) {
            onError.run();
        } finally {
            if (!closed) {
                try { codec.releaseOutputBuffer(index, false); }
                catch (RuntimeException failure) { onError.run(); }
            }
        }
    }

    @Override public void onOutputFormatChanged(MediaCodec ignored, MediaFormat format) {
        if (closed) return;
        try {
            byte[] sps = config(format.getByteBuffer("csd-0"));
            byte[] pps = config(format.getByteBuffer("csd-1"));
            if (sps == null || pps == null) onError.run();
            else queue.configure(FullAccessVideo.Packet.config(size, sps, pps));
        } catch (RuntimeException invalid) {
            onError.run();
        }
    }

    private static byte[] config(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() < 1 || buffer.remaining() > FullAccessVideo.MAX_CONFIG_BYTES)
            return null;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    @Override public void onError(MediaCodec ignored, MediaCodec.CodecException error) {
        if (!closed) onError.run();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { codec.release(); }
        finally { surface.release(); }
    }
}
