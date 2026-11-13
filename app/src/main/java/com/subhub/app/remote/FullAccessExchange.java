package com.subhub.app.remote;

import android.content.Context;
import android.os.Build;

import com.subhub.app.fullaccess.FullAccessDataSources;
import com.subhub.app.fullaccess.FullAccessFiles;
import com.subhub.app.fullaccess.FullAccessScreen;
import com.subhub.app.fullaccess.FullAccessVideo;

import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Read-only, opt-in requests outside the normal durable synchronization protocol. */
final class FullAccessExchange {
    private static final Set<String> SCOPES = Set.of("contacts", "notifications", "sms", "apps", "files", "screen");
    private static final int MAX_JPEG = 512 * 1024;

    private FullAccessExchange() { }

    static boolean validScope(String scope) { return scope != null && SCOPES.contains(scope); }

    static void serve(Context context, PeerTransport.Connection connection, String scope, BooleanSupplier current,
            BooleanSupplier foreground, boolean screenRequestStates, Consumer<AutoCloseable> ownSource) throws Exception {
        serve(context, connection, scope, current, foreground, screenRequestStates, false, ownSource);
    }

    static void serve(Context context, PeerTransport.Connection connection, String scope, BooleanSupplier current,
            BooleanSupplier foreground, boolean screenRequestStates, boolean screenVideoRequested,
            Consumer<AutoCloseable> ownSource) throws Exception {
        if (!current.getAsBoolean()) {
            RemoteProtocol.write(connection.output(), FullAccessChannel.frame(FullAccessChannel.OPENED)
                    .put("status", "disabled"));
            // Let the viewer read the status before retiring the connection.
            RemoteProtocol.read(connection.input());
            return;
        }
        boolean video = "screen".equals(scope) && screenVideoRequested && Build.VERSION.SDK_INT >= 29;
        FullAccessDataSources.ReadSession pages = new FullAccessDataSources.ReadSession();
        FullAccessFiles.Session files = null;
        FullAccessScreen.Session screen = null;
        try {
            if ("files".equals(scope)) {
                files = FullAccessFiles.open(context, current);
                ownSource.accept(files);
            }
            write(connection, FullAccessChannel.frame(FullAccessChannel.OPENED).put("status", "ready")
                    .put("detailVersion", FullAccessChannel.DETAIL_VERSION)
                    .put(FullAccessVideo.CAPABILITY, video)
                    .put("lazyContactPhotos", "contacts".equals(scope)).put("lazyAppUsage", "apps".equals(scope)), current);
            while (current.getAsBoolean()) {
                connection.setReadTimeoutMillis("screen".equals(scope) ? 4_000 : 12_000);
                JSONObject request = RemoteProtocol.read(connection.input());
                FullAccessChannel.require(request, FullAccessChannel.REQUEST);
                String operation = FullAccessChannel.text(request, "op");
                if ("files".equals(scope) && "keepalive".equals(operation)) {
                    respond(connection, new JSONObject().put("status", "ready"), current);
                } else if (files != null) {
                    String node = FullAccessChannel.text(request, "node");
                    if ("list".equals(operation)) {
                        respond(connection, files.list(node, FullAccessChannel.text(request, "cursor")), current);
                    } else if (FullAccessChannel.transferOp(operation)) {
                        transfer(connection, files, node, operation, current);
                    } else {
                        throw new IOException("Invalid file operation");
                    }
                } else if (video && screen != null && "video".equals(operation) && screen.videoReady()) {
                    respond(connection, new JSONObject().put("status", "video"), current);
                    streamVideo(connection, screen);
                    return;
                } else if ("screen".equals(scope) && "frame".equals(operation)) {
                    if (screen == null) {
                        screen = FullAccessScreen.open(context, current, foreground, video);
                        ownSource.accept(screen);
                    }
                    JSONObject response = new JSONObject().put("status", screen.statusForViewer(screenRequestStates));
                    if (video && screen.videoReady()) response.put("status", "video_ready");
                    byte[] image = screen.takeLatest();
                    if (image != null && image.length > 0 && image.length <= MAX_JPEG)
                        response.put("image", Base64.getEncoder().encodeToString(image));
                    respond(connection, response, current);
                } else {
                    String node = FullAccessChannel.text(request, "node");
                    if (!FullAccessDataSources.supportsRead(scope, operation, node))
                        throw new IOException("Invalid Full Access view operation");
                    respond(connection, FullAccessDataSources.page(context, scope, operation, node,
                            FullAccessChannel.text(request, "cursor"), pages), current);
                }
            }
        } finally {
            if (screen != null) screen.close();
            if (files != null) files.close();
        }
    }

    private static void streamVideo(PeerTransport.Connection connection, FullAccessScreen.Session screen) throws Exception {
        DataOutputStream wire = new DataOutputStream(connection.output());
        while (true) {
            // nextVideoPacket checks the exact grant after waiting for a packet and returns only END on loss.
            FullAccessVideo.Packet packet = screen.nextVideoPacket();
            connection.setReadTimeoutMillis(FullAccessVideo.TIMEOUT_MS);
            FullAccessVideo.write(wire, packet);
            if (packet.kind() == FullAccessVideo.END) return;
        }
    }

    private static void transfer(PeerTransport.Connection connection, FullAccessFiles.Session files,
            String node, String operation, BooleanSupplier current) throws Exception {
        String mime;
        try { mime = files.mime(node, operation); }
        catch (IOException | RuntimeException unavailable) {
            respond(connection, new JSONObject().put("status", "unavailable"), current);
            return;
        }
        respond(connection, new JSONObject().put("status", "transfer").put("mime", mime), current);
        FullAccessChannel.ChunkOutput output = new FullAccessChannel.ChunkOutput(connection, current);
        files.transfer(node, operation, output);
        output.complete();
    }

    private static void respond(PeerTransport.Connection connection, JSONObject response,
            BooleanSupplier current) throws Exception {
        write(connection, response.put("type", FullAccessChannel.RESPONSE)
                .put("version", FullAccessChannel.VERSION), current);
    }

    private static void write(PeerTransport.Connection connection, JSONObject response,
            BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("Full Access view closed");
        connection.setReadTimeoutMillis(8_000);
        RemoteProtocol.write(connection.output(), response);
    }
}
