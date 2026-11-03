package com.subhub.app.remote.icons;

import com.subhub.app.remote.PeerTransport;
import com.subhub.app.remote.RemoteProtocol;
import com.subhub.app.remote.RemoteStore;
import com.subhub.app.util.RemoteAppIcon;
import java.io.IOException;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional v1 phase only: DOM requests, SUB serves; caller owns both-sided capability negotiation. */
public final class AppIconExchange {
    public static final int VERSION = 1, MAX_REQUESTS = 8, MAX_SESSION_BYTES = 1_048_576, MAX_CHUNK_BYTES = 16_384;
    private static final int MAX_CHUNKS = 128, MAX_ICON_FRAME_BYTES = 32_768;

    public interface Authority {
        /** Recheck actual active backend peer/generation/role, never just the captured Peer.state. */
        boolean current(RemoteStore.Peer peer);
        /** Current peer-authorized, already published APP_CATALOG/EVENTS/APP_USAGE package reference. */
        boolean published(RemoteStore.Peer peer, String packageName);
        /** Current authorized mirror reference; retained history/hash/cache alone is insufficient. */
        boolean mirrored(RemoteStore.Peer peer, String packageName);
        /** Bounded prioritized DOM projection requests (maximum 8), not package inventory. */
        List<String> wanted(RemoteStore.Peer peer);
    }

    public interface Source {
        /** Fresh static raster PNG for this exact requested package; null means missing/invisible. */
        byte[] rasterize(RemoteStore.Peer peer, String packageName) throws Exception;
    }

    private final Authority authority;
    private final Source source;
    private final AppIconCache cache;
    private final AppIconCache.Validator validator;
    private final CopyOnWriteArrayList<Session> active = new CopyOnWriteArrayList<>();

    public AppIconExchange(Authority authority, Source source, AppIconCache cache) {
        this(authority, source, cache, IconPng::validate);
    }
    AppIconExchange(Authority authority, Source source, AppIconCache cache, AppIconCache.Validator validator) {
        this.authority = Objects.requireNonNull(authority); this.source = Objects.requireNonNull(source);
        this.cache = Objects.requireNonNull(cache); this.validator = Objects.requireNonNull(validator);
    }

    /**
     * Worker-only. Invoke AFTER controls/Tasks/Journal, BEFORE done, only when both advertise v1.
     * Any failure aborts the phase: caller closes this connection instead of continuing outer frames.
     */
    public void exchange(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadlineNanos) throws Exception {
        Session session = new Session(connection, peer, deadlineNanos);
        active.add(session);
        try {
            session.check();
            if (peer.localRole == RemoteProtocol.Role.DOM) receive(session);
            else if (peer.localRole == RemoteProtocol.Role.SUB) serve(session);
            else throw new SecurityException("Missing icon role");
        } finally {
            synchronized (session) { session.finished = true; }
            active.remove(session);
        }
    }

    /** Backend revoke hook, outside SQL/authority/UI locks. Cancels blocked phase I/O as well as delivery. */
    public void cancel(String peerIdentity, String generation) {
        for (Session session : active) {
            if (!session.peer.identity.equals(peerIdentity) || !session.peer.generation.equals(generation)) continue;
            synchronized (session) {
                if (session.finished) continue;
                session.cancelled = true;
                try { session.connection.close(); } catch (IOException ignored) { /* Guard still rejects delivery. */ }
            }
        }
    }

    private void receive(Session s) throws Exception {
        List<String> prioritized = Objects.requireNonNull(authority.wanted(s.peer));
        if (prioritized.size() > MAX_REQUESTS) throw bad("Too many icon requests");
        List<String> wanted = new ArrayList<>(prioritized);
        Set<String> unique = new HashSet<>();
        JSONArray packages = new JSONArray();
        AppIconCache.Snapshot token = cache.snapshot();
        for (String name : wanted) {
            RemoteAppIcon bare = icon(s, name, null);
            if (!unique.add(name)) throw bad("Duplicate icon request");
            s.mirror(name);
            if (!cache.containsReference(token, bare)) throw new SecurityException("Missing icon projection reference");
            packages.put(name);
        }
        s.send(s.message("icon_wants").put("packages", packages));
        for (String name : wanted) {
            s.mirror(name);
            if (!cache.containsReference(token, icon(s, name, null))) throw new SecurityException("Icon projection changed");
            JSONObject offer = s.read();
            s.mirror(name);
            requirePackage(offer, name);
            if ("icon_unavailable".equals(offer.getString("type"))) {
                // Remove a stale descriptor only while this exact reference epoch is still current.
                if (!cache.offered(token, icon(s, name, null))) throw new SecurityException("Icon projection changed");
                continue;
            }
            requireType(offer, "icon_offer");
            RemoteAppIcon identity = descriptor(s, offer, name);
            try {
                if (!cache.offered(token, identity)) throw new SecurityException("Icon projection changed");
                byte[] bytes = cache.resume(token, identity);
                s.mirror(name);
                s.send(described(s, "icon_resume", identity).put("offset", bytes.length)
                        .put("prefixHash", RemoteProtocol.sha256(bytes)));
                while (true) {
                    s.mirror(name);
                    JSONObject frame = s.read();
                    s.mirror(name);
                    if (!cache.currentDescriptor(token, identity)) throw new SecurityException("Icon projection changed");
                    if (!identity.equals(descriptor(s, frame, name))) throw bad("Conflicting icon descriptor");
                    String type = frame.getString("type");
                    if ("icon_refused".equals(type)) throw bad("Icon resume refused");
                    if ("icon_end".equals(type)) {
                        if (integer(frame, "offset", 0, RemoteAppIcon.MAX_BYTES) != bytes.length
                                || bytes.length != identity.descriptor.byteLength) throw bad("Incomplete icon end");
                        cache.complete(token, identity, bytes, () -> s.mirror(name));
                        s.send(described(s, "icon_ack", identity).put("offset", bytes.length));
                        break;
                    }
                    requireType(frame, "icon_chunk");
                    String encoded = frame.getString("data");
                    if (encoded.length() > ((MAX_CHUNK_BYTES + 2) / 3) * 4) throw bad("Oversized icon chunk");
                    byte[] chunk;
                    try { chunk = Base64.getDecoder().decode(encoded); }
                    catch (IllegalArgumentException malformed) { throw bad("Invalid icon chunk encoding"); }
                    if (!Base64.getEncoder().encodeToString(chunk).equals(encoded)) throw bad("Noncanonical icon chunk");
                    s.charge(chunk.length);
                    int offset = integer(frame, "offset", 0, identity.descriptor.byteLength);
                    if (offset > bytes.length || chunk.length > identity.descriptor.byteLength - offset)
                        throw bad("Invalid icon chunk range");
                    if (offset < bytes.length) {
                        if (chunk.length > bytes.length - offset
                                || !Arrays.equals(chunk, Arrays.copyOfRange(bytes, offset, offset + chunk.length)))
                            throw bad("Conflicting duplicate icon chunk");
                    } else {
                        byte[] next = Arrays.copyOf(bytes, bytes.length + chunk.length);
                        System.arraycopy(chunk, 0, next, bytes.length, chunk.length);
                        bytes = next;
                        cache.partial(token, identity, bytes);
                    }
                    s.send(described(s, "icon_ack", identity).put("offset", bytes.length));
                }
            } catch (ProtocolException | JSONException | IllegalArgumentException | SecurityException invalid) {
                cache.discard(identity);
                throw invalid;
            }
        }
        requireType(s.read(), "icons_complete");
        s.send(s.message("icons_ack"));
    }

    private void serve(Session s) throws Exception {
        JSONObject wants = s.read(); requireType(wants, "icon_wants");
        JSONArray packages = wants.getJSONArray("packages");
        if (packages.length() > MAX_REQUESTS) throw bad("Too many icon requests");
        Set<String> unique = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < packages.length(); i++) {
            Object value = packages.get(i);
            if (!(value instanceof String)) throw bad("Invalid package request");
            String name = (String) value;
            icon(s, name, null);
            if (!unique.add(name)) throw bad("Duplicate icon request");
            names.add(name);
        }
        for (String name : names) {
            s.check();
            if (!authority.published(s.peer, name)) {
                s.send(s.message("icon_unavailable").put("package", name)); continue;
            }
            s.published(name);
            byte[] png = source.rasterize(s.peer, name);
            s.published(name);
            if (png == null) { s.send(s.message("icon_unavailable").put("package", name)); continue; }
            if (png.length > RemoteAppIcon.MAX_BYTES || png.length < 57) throw bad("Invalid source icon length");
            // The fresh source's header supplies dimensions; descriptor/header/hash/decode are checked below.
            int width = number(png, 16), height = number(png, 20);
            RemoteAppIcon identity = icon(s, name, new RemoteAppIcon.Descriptor(RemoteProtocol.sha256(png), png.length, width, height));
            validator.validate(identity, png);
            s.published(name);
            s.send(described(s, "icon_offer", identity));
            JSONObject resume = s.read(); s.published(name); requireType(resume, "icon_resume");
            if (!identity.equals(descriptor(s, resume, name))) throw bad("Conflicting icon resume descriptor");
            int offset = integer(resume, "offset", 0, png.length);
            if (!RemoteProtocol.sha256(Arrays.copyOf(png, offset)).equals(resume.getString("prefixHash"))) {
                // Explicit refusal lets the receiver discard a poisoned partial without touching a complete PNG.
                s.send(described(s, "icon_refused", identity));
                throw bad("Conflicting icon resume prefix");
            }
            while (offset < png.length) {
                s.published(name);
                byte[] chunk = Arrays.copyOfRange(png, offset, Math.min(png.length, offset + MAX_CHUNK_BYTES));
                s.charge(chunk.length);
                s.send(described(s, "icon_chunk", identity).put("offset", offset)
                        .put("data", Base64.getEncoder().encodeToString(chunk)));
                offset += chunk.length;
                ack(s, identity, offset);
            }
            s.published(name);
            s.send(described(s, "icon_end", identity).put("offset", offset));
            ack(s, identity, offset);
        }
        s.send(s.message("icons_complete"));
        requireType(s.read(), "icons_ack");
    }

    private void ack(Session s, RemoteAppIcon identity, int offset) throws Exception {
        JSONObject ack = s.read(); s.published(identity.packageName); requireType(ack, "icon_ack");
        if (!identity.equals(descriptor(s, ack, identity.packageName))
                || integer(ack, "offset", 0, identity.descriptor.byteLength) != offset) throw bad("Invalid icon acknowledgement");
    }

    private static RemoteAppIcon icon(Session s, String name, RemoteAppIcon.Descriptor descriptor) {
        return new RemoteAppIcon(s.peer.identity, s.peer.generation, name, descriptor);
    }
    private static RemoteAppIcon descriptor(Session s, JSONObject frame, String name) throws JSONException, ProtocolException {
        requirePackage(frame, name);
        if (!"png".equals(frame.getString("format"))) throw bad("Unsupported icon format");
        return icon(s, name, new RemoteAppIcon.Descriptor(frame.getString("hash"),
                integer(frame, "length", 57, RemoteAppIcon.MAX_BYTES),
                integer(frame, "width", 1, RemoteAppIcon.MAX_PIXELS), integer(frame, "height", 1, RemoteAppIcon.MAX_PIXELS)));
    }
    private static JSONObject described(Session s, String type, RemoteAppIcon identity) throws JSONException {
        RemoteAppIcon.Descriptor d = identity.descriptor;
        return s.message(type).put("package", identity.packageName).put("format", "png").put("hash", d.sha256)
                .put("length", d.byteLength).put("width", d.width).put("height", d.height);
    }
    private static int integer(JSONObject frame, String key, int min, int max) throws JSONException, ProtocolException {
        long value = RemoteProtocol.integer(frame, key);
        if (value < min || value > max) throw bad("Invalid icon " + key);
        return (int) value;
    }
    private static int number(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16 | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }
    private static void requirePackage(JSONObject frame, String name) throws JSONException, ProtocolException {
        if (!name.equals(frame.getString("package"))) throw bad("Unexpected icon package");
    }
    private static void requireType(JSONObject frame, String type) throws JSONException, ProtocolException {
        if (!type.equals(frame.getString("type"))) throw bad("Unexpected icon frame");
    }
    private static ProtocolException bad(String message) { return new ProtocolException(message); }

    private final class Session {
        final PeerTransport.Connection connection;
        final RemoteStore.Peer peer;
        final long deadline;
        int bytes, chunks;
        volatile boolean cancelled;
        boolean finished;
        Session(PeerTransport.Connection connection, RemoteStore.Peer peer, long deadline) {
            this.connection = Objects.requireNonNull(connection); this.peer = Objects.requireNonNull(peer); this.deadline = deadline;
        }
        void check() throws SocketTimeoutException {
            if (cancelled || !peer.identity.equals(connection.authenticatedPeerIdentity()) || !authority.current(peer))
                throw new SecurityException("Icon peer is no longer current");
            remaining();
        }
        void published(String name) throws SocketTimeoutException { check(); if (!authority.published(peer, name)) throw new SecurityException("Icon publication revoked"); }
        void mirror(String name) throws SocketTimeoutException { check(); if (!authority.mirrored(peer, name)) throw new SecurityException("Icon mirror revoked"); }
        long remaining() throws SocketTimeoutException {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SocketTimeoutException("Icon phase deadline expired");
            return Math.min(10_000, Math.max(1, left / 1_000_000));
        }
        void timeout() throws SocketTimeoutException { connection.setReadTimeoutMillis(remaining()); }
        JSONObject message(String type) throws JSONException {
            return new JSONObject().put("version", RemoteProtocol.VERSION).put("appIconsVersion", VERSION)
                    .put("generation", peer.generation).put("type", type);
        }
        void scope(JSONObject frame) throws SocketTimeoutException {
            String name = frame.optString("package", "");
            if (!name.isEmpty() && !"icon_unavailable".equals(frame.optString("type"))) {
                if (peer.localRole == RemoteProtocol.Role.DOM) mirror(name); else published(name);
            }
        }
        void send(JSONObject frame) throws IOException {
            check(); scope(frame); timeout(); RemoteProtocol.write(connection.output(), frame); check(); scope(frame); timeout();
        }
        JSONObject read() throws IOException, JSONException {
            check(); timeout(); JSONObject frame = RemoteProtocol.read(connection.input()); check(); timeout();
            if (frame.toString().getBytes(StandardCharsets.UTF_8).length > MAX_ICON_FRAME_BYTES) throw bad("Oversized icon frame");
            if (!peer.generation.equals(frame.getString("generation"))
                    || RemoteProtocol.integer(frame, "appIconsVersion") != VERSION) throw bad("Wrong icon scope/version");
            scope(frame);
            return frame;
        }
        void charge(int size) throws ProtocolException {
            if (size < 1 || size > MAX_CHUNK_BYTES || bytes > MAX_SESSION_BYTES - size || ++chunks > MAX_CHUNKS)
                throw bad("Icon session budget exceeded");
            bytes += size;
        }
    }
}
