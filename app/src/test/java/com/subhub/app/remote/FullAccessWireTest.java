package com.subhub.app.remote;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class FullAccessWireTest {
    @Test public void successfulTransferHasBoundedChunksAndTerminalMarker() throws Exception {
        TestConnection connection = new TestConnection();
        FullAccessChannel.ChunkOutput output = new FullAccessChannel.ChunkOutput(connection, () -> true);
        byte[] payload = new byte[FullAccessChannel.CHUNK_BYTES + 3];
        Arrays.fill(payload, (byte) 61);
        output.write(payload);
        output.complete();

        DataInputStream input = connection.received();
        assertEquals(FullAccessChannel.CHUNK_BYTES, input.readInt());
        byte[] bytes = new byte[FullAccessChannel.CHUNK_BYTES];
        input.readFully(bytes);
        assertArrayEquals(Arrays.copyOf(payload, FullAccessChannel.CHUNK_BYTES), bytes);
        assertEquals(3, input.readInt());
        byte[] tail = new byte[3];
        input.readFully(tail);
        assertArrayEquals(new byte[] {61, 61, 61}, tail);
        assertEquals(0, input.readInt());
        assertEquals(-1, input.read());
    }

    @Test public void revokedTransferCannotFlushBufferedDataOrSignalSuccess() throws Exception {
        TestConnection connection = new TestConnection();
        AtomicBoolean current = new AtomicBoolean(true);
        FullAccessChannel.ChunkOutput output = new FullAccessChannel.ChunkOutput(connection, current::get);
        output.write(new byte[] {1, 2});
        output.flush();
        output.write(new byte[] {3, 4});
        current.set(false);
        assertThrows(IOException.class, output::complete);
        DataInputStream input = connection.received();
        assertEquals(2, input.readInt());
        assertEquals(1, input.read());
        assertEquals(2, input.read());
        assertEquals(-1, input.read());
    }

    @Test public void unsupportedPeerStatusHasNoTransportAndCannotTransfer() throws Exception {
        FullAccessChannel channel = FullAccessChannel.denied("files", "update_required");
        assertEquals("update_required", channel.query(new JSONObject().put("op", "list")).getString("status"));
        assertThrows(IOException.class,
                () -> channel.transfer(new JSONObject().put("op", "download"), new ByteArrayOutputStream()));
        channel.close();
        assertFalse(channel.isOpen());
        assertThrows(IOException.class, () -> channel.query(new JSONObject().put("op", "list")));
    }

    private static final class TestConnection implements PeerTransport.Connection {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        DataInputStream received() { return new DataInputStream(new ByteArrayInputStream(output.toByteArray())); }

        @Override public String authenticatedPeerIdentity() { return ""; }
        @Override public InputStream input() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream output() { return output; }
        @Override public void setReadTimeoutMillis(long timeoutMillis) { }
        @Override public boolean isDirect() { return true; }
        @Override public void close() { }
    }
}
