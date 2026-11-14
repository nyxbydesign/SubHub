package com.subhub.app.fullaccess;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** Decode on the screen channel's worker, with no decoded bitmap or UI-frame queue. */
final class FullAccessVideoDecoder implements AutoCloseable {
    private static final long WAIT_US = 20_000;
    private final Surface surface;
    private final BooleanSupplier current;
    private final Runnable hide;
    private final Runnable render;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private MediaCodec codec;
    private boolean started;

    FullAccessVideoDecoder(Surface surface, BooleanSupplier current, Runnable hide, Runnable render) {
        this.surface = surface;
        this.current = current;
        this.hide = hide;
        this.render = render;
    }

    void accept(FullAccessVideo.Packet packet) throws IOException {
        // The channel checks authority after reading every packet. Recheck just before rendering pixels.
        if (!surface.isValid()) throw new IOException("Screen video closed");
        switch (packet.kind()) {
            case FullAccessVideo.CONFIG -> configure(packet);
            case FullAccessVideo.HIDDEN -> {
                hide.run();
                close();
            }
            case FullAccessVideo.FRAME, FullAccessVideo.KEY_FRAME -> decode(packet);
            case FullAccessVideo.KEEPALIVE -> drain(0);
            default -> throw new IOException("Unexpected screen packet");
        }
    }

    private void configure(FullAccessVideo.Packet packet) throws IOException {
        hide.run();
        close();
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                packet.size().width(), packet.size().height());
        format.setByteBuffer("csd-0", ByteBuffer.wrap(packet.first()));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(packet.second()));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FullAccessVideo.MAX_FRAME_BYTES);
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, surface, null, 0);
        codec.start();
        started = false;
    }

    private void decode(FullAccessVideo.Packet packet) throws IOException {
        if (codec == null || !started && packet.kind() != FullAccessVideo.KEY_FRAME) return;
        drain(0);
        int index = codec.dequeueInputBuffer(WAIT_US);
        if (index < 0) throw new IOException("Screen decoder cannot keep up");
        ByteBuffer input = codec.getInputBuffer(index);
        if (input == null || input.capacity() < packet.first().length)
            throw new IOException("Invalid screen decoder buffer");
        input.clear();
        input.put(packet.first());
        codec.queueInputBuffer(index, 0, packet.first().length, packet.timeUs(), 0);
        started = true;
        drain(WAIT_US);
    }

    private void drain(long timeoutUs) throws IOException {
        if (codec == null) return;
        for (int drained = 0; drained < 8; drained++) {
            int output = codec.dequeueOutputBuffer(info, timeoutUs);
            timeoutUs = 0;
            if (output == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (output < 0) continue;
            boolean allowed = current.getAsBoolean();
            codec.releaseOutputBuffer(output, allowed);
            if (!allowed) throw new IOException("Screen video closed");
            render.run();
        }
    }

    @Override public void close() {
        MediaCodec owned = codec;
        codec = null;
        if (owned != null) owned.release();
    }
}
