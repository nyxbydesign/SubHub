package com.subhub.app.remote;

import android.content.Context;

import com.subhub.app.fullaccess.FullAccessDataSources;
import com.subhub.app.fullaccess.FullAccessFiles;
import com.subhub.app.fullaccess.FullAccessScreen;

import org.json.JSONObject;

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
            BooleanSupplier foreground, Consumer<AutoCloseable> ownSource) throws Exception {
        if (!current.getAsBoolean()) {
            RemoteProtocol.write(connection.output(), FullAccessChannel.frame(FullAccessChannel.OPENED)
                    .put("status", "disabled"));
            // Let the viewer read the status before retiring the connection.
            RemoteProtocol.read(connection.input());
            return;
        }
        FullAccessFiles.Session files = null;
        FullAccessScreen.Session screen = null;
        try {
            if ("files".equals(scope)) {
                files = FullAccessFiles.open(context, current);
                ownSource.accept(files);
            }
            write(connection, FullAccessChannel.frame(FullAccessChannel.OPENED).put("status", "ready"), current);
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
                } else if ("screen".equals(scope) && "frame".equals(operation)) {
                    if (screen == null) {
                        screen = FullAccessScreen.open(context, current, foreground);
                        ownSource.accept(screen);
                    }
                    JSONObject response = new JSONObject().put("status", screen.status());
                    byte[] image = screen.takeLatest();
                    if (image != null && image.length > 0 && image.length <= MAX_JPEG)
                        response.put("image", Base64.getEncoder().encodeToString(image));
                    respond(connection, response, current);
                } else if (!"screen".equals(scope) && "page".equals(operation)) {
                    respond(connection, FullAccessDataSources.page(context, scope,
                            FullAccessChannel.text(request, "cursor")), current);
                } else {
                    throw new IOException("Invalid Full Access view operation");
                }
            }
        } finally {
            if (screen != null) screen.close();
            if (files != null) files.close();
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
