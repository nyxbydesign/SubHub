package com.subhub.app.remote;

import com.subhub.app.fullaccess.FullAccessDataSources;
import com.subhub.app.fullaccess.FullAccessVideo;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** One foreground viewer, one authenticated peer, and one read-only scope. */
public final class FullAccessChannel implements AutoCloseable {
    static final String OPEN = "full_access_open";
    static final String OPENED = "full_access_opened";
    static final String REQUEST = "full_access_request";
    static final String RESPONSE = "full_access_response";
    static final int VERSION = 1;
    static final int DETAIL_VERSION = 1;
    static final int CHUNK_BYTES = 32 * 1024;
    private static final long MAX_BYTES = 32L * 1024 * 1024 * 1024;
    private static final long MAX_TRANSFER_NANOS = TimeUnit.MINUTES.toNanos(15);
    private static final int IDLE_MILLIS = 12_000;
    private final PeerTransport.Connection connection;
    private final BooleanSupplier current;
    private final Runnable onClose;
    private final String scope;
    private final JSONObject opened;
    private final ReentrantLock io = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastActivity = System.nanoTime();
    private volatile ScheduledFuture<?> keepalive;

    FullAccessChannel(PeerTransport.Connection connection, String scope, JSONObject opened,
            BooleanSupplier current, Runnable onClose) {
        this.connection = connection;
        this.scope = scope;
        this.opened = opened;
        this.current = current;
        this.onClose = onClose;
    }

    static FullAccessChannel denied(String scope, String status) {
        try {
            return new FullAccessChannel(null, scope, new JSONObject().put("status", status), () -> true, () -> {});
        } catch (JSONException invalid) { throw new IllegalArgumentException("Unknown status", invalid); }
    }

    public boolean isOpen() { return !closed.get(); }
    public boolean supportsLazyContactPhotos() { return opened.optBoolean("lazyContactPhotos"); }
    public boolean supportsLazyAppUsage() { return opened.optBoolean("lazyAppUsage"); }

    public boolean supportsScreenVideo() {
        return "screen".equals(scope) && Boolean.TRUE.equals(opened.opt(FullAccessVideo.CAPABILITY));
    }

    @FunctionalInterface public interface VideoConsumer {
        void accept(FullAccessVideo.Packet packet) throws IOException;
    }

    /** Transfers the opted-in screen channel from request/reply to a bounded packet stream. */
    public void streamScreen(VideoConsumer consumer) throws IOException {
        io.lock();
        try {
            requireCurrent();
            if (!supportsScreenVideo() || !"ready".equals(opened.optString("status")))
                throw new IOException("Screen video unavailable");
            JSONObject response = exchange("video", "", "");
            if (!"video".equals(response.optString("status"))) throw new IOException("Screen video declined");
            DataInputStream wire = new DataInputStream(connection.input());
            while (!closed.get()) {
                connection.setReadTimeoutMillis(FullAccessVideo.TIMEOUT_MS);
                FullAccessVideo.Packet packet = FullAccessVideo.read(wire);
                requireCurrent();
                if (packet.kind() == FullAccessVideo.END) return;
                consumer.accept(packet);
            }
        } finally {
            io.unlock();
            close();
        }
    }

    /** Worker-only check for publication after the transfer's terminal marker. */
    public boolean isCurrent() { return !closed.get() && current.getAsBoolean(); }

    void keepAlive(ScheduledExecutorService timer, java.util.concurrent.Executor worker) {
        ScheduledFuture<?> task = timer.scheduleWithFixedDelay(() -> {
            if (closed.get() || System.nanoTime() - lastActivity < TimeUnit.SECONDS.toNanos(3)) return;
            try {
                worker.execute(() -> {
                    if (closed.get() || !io.tryLock()) return;
                    try {
                        if (closed.get() || System.nanoTime() - lastActivity < TimeUnit.SECONDS.toNanos(3)) return;
                        try { exchange("keepalive", "", ""); }
                        catch (IOException unavailable) { close(); }
                    } finally { io.unlock(); }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // A running file transfer already owns this connection.
            }
        }, 4, 4, TimeUnit.SECONDS);
        keepalive = task;
        if (closed.get()) task.cancel(false);
    }

    public JSONObject query(JSONObject request) throws IOException {
        io.lock();
        try {
            requireCurrent();
            if (!"ready".equals(opened.optString("status"))) return opened;
            String operation = text(request, "op");
            String node = text(request, "node");
            if (!queryOp(scope, operation, node)) throw new IOException("Invalid view operation");
            if (isDetail(operation) && opened.optInt("detailVersion") != DETAIL_VERSION)
                return deniedDetails();
            return exchange(operation, node, text(request, "cursor"));
        } finally { io.unlock(); }
    }

    static boolean queryOp(String scope, String operation, String node) {
        if ("files".equals(scope)) return "list".equals(operation);
        if ("screen".equals(scope)) return "frame".equals(operation) && node.isEmpty();
        return FullAccessDataSources.supportsRead(scope, operation, node);
    }

    private static boolean isDetail(String operation) {
        return "conversations".equals(operation) || "thread".equals(operation)
                || "groups".equals(operation) || "group".equals(operation);
    }

    private static JSONObject deniedDetails() throws IOException {
        try { return new JSONObject().put("status", "update_required"); }
        catch (JSONException invalid) { throw new IOException("Could not describe view status", invalid); }
    }

    public void transfer(JSONObject request, OutputStream destination) throws IOException {
        io.lock();
        try {
            requireCurrent();
            String operation = text(request, "op");
            if (!"ready".equals(opened.optString("status")) || !"files".equals(scope) || !transferOp(operation))
                throw new IOException("Transfer unavailable");
            try {
                JSONObject response = exchange(operation, text(request, "node"), "");
                if (!"transfer".equals(response.optString("status"))) throw new IOException("File unavailable");
                DataInputStream input = new DataInputStream(connection.input());
                byte[] chunk = new byte[CHUNK_BYTES];
                long deadline = System.nanoTime() + MAX_TRANSFER_NANOS;
                long total = 0;
                while (true) {
                    requireCurrent();
                    transferTimeout(connection, deadline);
                    int length = input.readInt();
                    if (length == 0) break;
                    if (length < 0 || length > chunk.length || total > MAX_BYTES - length)
                        throw new IOException("Invalid file transfer");
                    input.readFully(chunk, 0, length);
                    requireCurrent();
                    destination.write(chunk, 0, length);
                    total += length;
                }
                requireCurrent();
                destination.flush();
                lastActivity = System.nanoTime();
            } catch (IOException | RuntimeException failure) {
                close();
                throw failure;
            }
        } finally { io.unlock(); }
    }

    private JSONObject exchange(String operation, String node, String cursor) throws IOException {
        try {
            requireCurrent();
            connection.setReadTimeoutMillis(IDLE_MILLIS);
            RemoteProtocol.write(connection.output(), frame(REQUEST).put("op", operation)
                    .put("node", node).put("cursor", cursor));
            JSONObject response = RemoteProtocol.read(connection.input());
            require(response, RESPONSE);
            requireCurrent();
            lastActivity = System.nanoTime();
            return response;
        } catch (JSONException | RuntimeException invalid) {
            close();
            throw new IOException("Full Access response unavailable");
        } catch (IOException unavailable) {
            close();
            throw unavailable;
        }
    }

    private void requireCurrent() throws IOException {
        if (closed.get() || !current.getAsBoolean()) {
            close();
            throw new IOException("Full Access view closed");
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (keepalive != null) keepalive.cancel(false);
        try { if (connection != null) connection.close(); }
        catch (IOException ignored) { }
        finally { onClose.run(); }
    }

    static JSONObject frame(String type) throws JSONException {
        return new JSONObject().put("type", type).put("version", VERSION);
    }

    static void require(JSONObject frame, String type) throws JSONException {
        if (!type.equals(frame.get("type")) || RemoteProtocol.integer(frame, "version") != VERSION)
            throw new JSONException("Unsupported Full Access frame");
    }

    static String text(JSONObject frame, String key) throws IOException {
        Object value = frame.opt(key);
        if (value == null) return "";
        if (!(value instanceof String) || ((String) value).length() > 512)
            throw new IOException("Invalid Full Access request");
        return (String) value;
    }

    static boolean transferOp(String operation) {
        return "preview".equals(operation) || "download".equals(operation) || "archive".equals(operation);
    }

    static void transferTimeout(PeerTransport.Connection connection, long deadline) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("File transfer expired");
        connection.setReadTimeoutMillis(Math.min(IDLE_MILLIS, remaining));
    }

    /** Sends a success terminator only after the provider has completed successfully. */
    static final class ChunkOutput extends OutputStream {
        private final PeerTransport.Connection connection;
        private final DataOutputStream wire;
        private final BooleanSupplier current;
        private final byte[] chunk = new byte[CHUNK_BYTES];
        private final long deadline = System.nanoTime() + MAX_TRANSFER_NANOS;
        private int used;
        private long total;

        ChunkOutput(PeerTransport.Connection connection, BooleanSupplier current) throws IOException {
            this.connection = connection;
            wire = new DataOutputStream(connection.output());
            this.current = current;
        }

        @Override public void write(int value) throws IOException {
            if (used == chunk.length) flush();
            chunk[used++] = (byte) value;
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > data.length - length)
                throw new IndexOutOfBoundsException();
            while (length > 0) {
                if (used == chunk.length) flush();
                int amount = Math.min(chunk.length - used, length);
                System.arraycopy(data, offset, chunk, used, amount);
                used += amount;
                offset += amount;
                length -= amount;
            }
        }

        @Override public void flush() throws IOException {
            if (used == 0) return;
            check();
            if (total > MAX_BYTES - used) throw new IOException("File too large");
            wire.writeInt(used);
            wire.write(chunk, 0, used);
            wire.flush();
            total += used;
            used = 0;
        }

        void complete() throws IOException {
            flush();
            check();
            wire.writeInt(0);
            wire.flush();
        }

        private void check() throws IOException {
            if (!current.getAsBoolean()) throw new IOException("Full Access view closed");
            transferTimeout(connection, deadline);
        }
    }
}
