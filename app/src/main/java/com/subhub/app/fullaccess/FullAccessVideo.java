package com.subhub.app.fullaccess;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/** Wire limits and the one-projection, bounded AVC handoff. */
public final class FullAccessVideo {
    public static final String CAPABILITY = "screenAvcV1";
    public static final int FRAME_RATE = 25;
    public static final int MAX_FRAME_BYTES = 256 * 1024;
    public static final int MAX_CONFIG_BYTES = 4096;
    public static final int TIMEOUT_MS = 2500;
    public static final int KEEPALIVE = 0;
    public static final int CONFIG = 1;
    public static final int FRAME = 2;
    public static final int KEY_FRAME = 3;
    public static final int HIDDEN = 4;
    public static final int END = 5;
    private static final int MAX_PENDING_FRAMES = 6;

    private FullAccessVideo() { }

    public record Size(int width, int height) {
        public boolean valid() {
            return width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0
                    && Math.min(width, height) <= 720 && Math.max(width, height) <= 1280;
        }
    }

    static Size fit(int width, int height, int shortEdge, int alignWidth, int alignHeight) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid display size");
        int longEdge = shortEdge == 720 ? 1280 : 746;
        double scale = Math.min(1d, Math.min((double) shortEdge / Math.min(width, height),
                (double) longEdge / Math.max(width, height)));
        int w = (int) Math.floor(width * scale + 1e-6);
        int h = (int) Math.floor(height * scale + 1e-6);
        int aw = Math.max(2, alignWidth);
        int ah = Math.max(2, alignHeight);
        return new Size(w - w % aw, h - h % ah);
    }

    public record Packet(int kind, Size size, long timeUs, byte[] first, byte[] second) {
        static Packet config(Size size, byte[] sps, byte[] pps) { return new Packet(CONFIG, size, 0, sps, pps); }
        static Packet sample(boolean key, long timeUs, byte[] bytes) {
            return new Packet(key ? KEY_FRAME : FRAME, null, timeUs, bytes, null);
        }
    }

    static Packet signal(int kind) { return new Packet(kind, null, 0, null, null); }

    public static Packet read(DataInputStream input) throws IOException {
        int kind = input.readUnsignedByte();
        return switch (kind) {
            case KEEPALIVE, HIDDEN, END -> signal(kind);
            case CONFIG -> {
                Size size = new Size(input.readUnsignedShort(), input.readUnsignedShort());
                if (!size.valid()) throw new IOException("Invalid screen stream dimensions");
                yield Packet.config(size, readBytes(input, MAX_CONFIG_BYTES), readBytes(input, MAX_CONFIG_BYTES));
            }
            case FRAME, KEY_FRAME -> {
                long timeUs = input.readLong();
                if (timeUs < 0) throw new IOException("Invalid screen stream timestamp");
                yield Packet.sample(kind == KEY_FRAME, timeUs, readBytes(input, MAX_FRAME_BYTES));
            }
            default -> throw new IOException("Invalid screen stream packet");
        };
    }

    public static void write(DataOutputStream output, Packet packet) throws IOException {
        output.writeByte(packet.kind);
        if (packet.kind == CONFIG) {
            output.writeShort(packet.size.width);
            output.writeShort(packet.size.height);
            writeBytes(output, packet.first);
            writeBytes(output, packet.second);
        } else if (packet.kind == FRAME || packet.kind == KEY_FRAME) {
            output.writeLong(packet.timeUs);
            writeBytes(output, packet.first);
        }
        output.flush();
    }

    private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum) throw new IOException("Invalid screen stream packet size");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    /** Overflow discards the dependency chain. The next key frame starts a fresh decoder. */
    static final class Queue {
        private final ArrayDeque<Packet> pending = new ArrayDeque<>();
        private Packet config;
        private boolean needsKey = true;
        private boolean hidden;
        private boolean closed;

        synchronized void configure(Packet next) {
            config = next;
            pending.clear();
            needsKey = true;
        }

        synchronized void hide() {
            if (closed) return;
            pending.clear();
            hidden = true;
            needsKey = true;
            notifyAll();
        }

        synchronized void publish(Packet frame) {
            if (closed || config == null) return;
            if (pending.size() >= MAX_PENDING_FRAMES) {
                pending.clear();
                needsKey = true;
            }
            if (needsKey) {
                if (frame.kind != KEY_FRAME) return;
                pending.add(config);
                needsKey = false;
            }
            pending.add(frame);
            notifyAll();
        }

        synchronized Packet next() throws InterruptedException {
            if (pending.isEmpty() && !hidden && !closed) wait(500);
            if (closed) return signal(END);
            if (hidden) {
                hidden = false;
                return signal(HIDDEN);
            }
            return pending.isEmpty() ? signal(KEEPALIVE) : pending.removeFirst();
        }

        synchronized void close() {
            closed = true;
            pending.clear();
            config = null;
            notifyAll();
        }
    }
}
