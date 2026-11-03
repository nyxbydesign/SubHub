package com.subhub.app.remote.icons;

import com.subhub.app.remote.PeerTransport;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.RemoteAppIcon;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.DataOutputStream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.json.JSONObject;

/** Controlled authenticated byte streams; every message crosses RemoteProtocol's actual framing. */
final class IconFixtures {
    static final String SUB = String.format("%064x", 1), DOM = String.format("%064x", 2), GENERATION = "generation_a", PACKAGE = "example.shared";
    private IconFixtures() { }

    static byte[] png(int width, int height, int seed) throws IOException {
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            pixels.write(0); // PNG filter: none.
            for (int x = 0; x < width; x++) {
                int pixel = random.nextInt();
                pixels.write(pixel >>> 24); pixels.write(pixel >>> 16); pixels.write(pixel >>> 8); pixels.write(pixel);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { pixels.writeTo(deflater); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream png = new DataOutputStream(output); png.writeLong(0x89504e470d0a1a0aL);
        ByteArrayOutputStream header = new ByteArrayOutputStream(); DataOutputStream ihdr = new DataOutputStream(header);
        ihdr.writeInt(width); ihdr.writeInt(height); ihdr.write(new byte[] {8, 6, 0, 0, 0});
        pngChunk(png, 0x49484452, header.toByteArray()); pngChunk(png, 0x49444154, compressed.toByteArray()); pngChunk(png, 0x49454e44, new byte[0]);
        return output.toByteArray();
    }
    private static void pngChunk(DataOutputStream output, int type, byte[] data) throws IOException {
        output.writeInt(data.length); output.writeInt(type); output.write(data);
        CRC32 crc = new CRC32(); crc.update(new byte[] {(byte) (type >>> 24), (byte) (type >>> 16), (byte) (type >>> 8), (byte) type});
        crc.update(data); output.writeInt((int) crc.getValue());
    }
    static RemoteAppIcon icon(String peer, String generation, String name, byte[] png, int width, int height) {
        return new RemoteAppIcon(peer, generation, name,
                new RemoteAppIcon.Descriptor(RemoteProtocol.sha256(png), png.length, width, height));
    }
    static RemoteAppIcon bare() { return new RemoteAppIcon(SUB, GENERATION, PACKAGE, null); }

    // Peer construction is package-private in the incumbent's namespace; no production access is widened.
    static RemoteStore.Peer peer(RemoteProtocol.Role role) throws Exception {
        Constructor<RemoteStore.Peer> constructor = RemoteStore.Peer.class.getDeclaredConstructor(String.class, String.class,
                String.class, RemoteProtocol.Role.class, RemoteStore.State.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(role == RemoteProtocol.Role.DOM ? SUB : DOM, GENERATION, "Synthetic",
                role, RemoteStore.State.ACTIVE, 0L, "");
    }
    static JSONObject frame(String type) throws Exception {
        return new JSONObject().put("version", 1).put("appIconsVersion", 1).put("generation", GENERATION).put("type", type);
    }
    static JSONObject described(String type, RemoteAppIcon icon) throws Exception {
        return frame(type).put("package", icon.packageName).put("format", "png").put("hash", icon.descriptor.sha256)
                .put("length", icon.descriptor.byteLength).put("width", icon.descriptor.width).put("height", icon.descriptor.height);
    }
    static AppIconCache cache(java.io.File directory) throws IOException { return new AppIconCache(directory, RemoteAppIcon::validatePng); }
    static AppIconExchange exchange(Authority authority, AppIconExchange.Source source, AppIconCache cache) {
        return new AppIconExchange(authority, source, cache, RemoteAppIcon::validatePng);
    }
    static final class Authority implements AppIconExchange.Authority {
        volatile boolean active = true, published = true, mirrored = true;
        List<String> wants = Collections.singletonList(PACKAGE);
        @Override public boolean current(RemoteStore.Peer peer) { return active; }
        @Override public boolean published(RemoteStore.Peer peer, String name) { return published && wants.contains(name); }
        @Override public boolean mirrored(RemoteStore.Peer peer, String name) { return mirrored && wants.contains(name); }
        @Override public List<String> wanted(RemoteStore.Peer peer) { return wants; }
    }
    interface Action { void run(Connection connection, long deadline) throws Exception; }
    static final class Outcome {
        final Throwable dom, sub;
        final List<JSONObject> fromDom, fromSub;
        Outcome(Throwable dom, Throwable sub, List<JSONObject> fromDom, List<JSONObject> fromSub) {
            this.dom = dom; this.sub = sub; this.fromDom = fromDom; this.fromSub = fromSub;
        }
    }
    static Outcome duplex(Action dom, Action sub) throws Exception {
        Connection[] pair = pair();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        pair[0].deadline = deadline;
        pair[1].deadline = deadline;
        try {
            Future<Throwable> domRun = executor.submit(() -> execute(dom, pair[0], deadline));
            Future<Throwable> subRun = executor.submit(() -> execute(sub, pair[1], deadline));
            return new Outcome(domRun.get(5, TimeUnit.SECONDS), subRun.get(5, TimeUnit.SECONDS), pair[0].sent, pair[1].sent);
        } finally { pair[0].close(); pair[1].close(); executor.shutdownNow(); }
    }
    private static Throwable execute(Action action, Connection connection, long deadline) {
        try { action.run(connection, deadline); return null; }
        catch (Throwable failure) { return failure; }
        finally { connection.close(); }
    }
    static Connection[] pair() {
        BlockingQueue<byte[]> toDom = new LinkedBlockingQueue<>(), toSub = new LinkedBlockingQueue<>();
        return new Connection[] {new Connection(SUB, toDom, toSub), new Connection(DOM, toSub, toDom)};
    }
    static final class Connection implements PeerTransport.Connection {
        private static final byte[] END = new byte[0];
        final String identity;
        final BlockingQueue<byte[]> incoming, outgoing;
        final List<JSONObject> sent = new CopyOnWriteArrayList<>();
        volatile long deadline = Long.MAX_VALUE;
        volatile boolean closed;
        private byte[] current = END;
        private int offset;
        private boolean ended;
        Connection(String identity, BlockingQueue<byte[]> incoming, BlockingQueue<byte[]> outgoing) {
            this.identity = identity; this.incoming = incoming; this.outgoing = outgoing;
        }
        @Override public String authenticatedPeerIdentity() { return identity; }
        @Override public void setReadTimeoutMillis(long millis) { deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
        @Override public boolean isDirect() { return true; }
        @Override public InputStream input() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] bytes, int start, int length) throws IOException {
                    if (length == 0) return 0;
                    while (offset == current.length) {
                        if (closed || ended) return -1;
                        long left = deadline - System.nanoTime();
                        if (left <= 0) throw new SocketTimeoutException("Fixture frame expired");
                        try { current = incoming.poll(left, TimeUnit.NANOSECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                        if (current == null) throw new SocketTimeoutException("Fixture frame expired");
                        offset = 0;
                        if (current == END) { ended = true; return -1; }
                    }
                    int count = Math.min(length, current.length - offset);
                    System.arraycopy(current, offset, bytes, start, count); offset += count; return count;
                }
            };
        }
        @Override public OutputStream output() {
            return new OutputStream() {
                private final ByteArrayOutputStream trace = new ByteArrayOutputStream();
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] bytes, int start, int length) throws IOException {
                    if (closed || System.nanoTime() >= deadline) throw new IOException("Fixture connection closed/expired");
                    if (length > 0) { trace.write(bytes, start, length); outgoing.add(Arrays.copyOfRange(bytes, start, start + length)); }
                }
                @Override public void flush() throws IOException {
                    sent.add(RemoteProtocol.read(new java.io.ByteArrayInputStream(trace.toByteArray())));
                    trace.reset();
                }
            };
        }
        @Override public void close() { closed = true; incoming.add(END); outgoing.add(END); }
    }
}
