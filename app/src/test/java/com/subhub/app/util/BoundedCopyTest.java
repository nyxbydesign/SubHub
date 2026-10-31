package com.subhub.app.util;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public final class BoundedCopyTest {
    @Test public void acceptsExactLimitAndEmptyStream() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[65537];
        bytes[65536] = 42;
        assertEquals(bytes.length, BoundedCopy.copy(new ByteArrayInputStream(bytes), output, bytes.length));
        assertArrayEquals(bytes, output.toByteArray());
        assertEquals(0, BoundedCopy.copy(new ByteArrayInputStream(new byte[0]), output, 0));
    }

    @Test public void readsAtMostOneExcessByteWithoutPublishingIt() throws Exception {
        long[] read = {0};
        InputStream unknownSize = new InputStream() {
            @Override public int read() { read[0]++; return 42; }
            @Override public int read(byte[] b, int off, int len) {
                java.util.Arrays.fill(b, off, off + len, (byte) 42);
                read[0] += len;
                return len;
            }
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try { BoundedCopy.copy(unknownSize, output, 65537); fail("Oversized stream accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("limit")); }
        assertEquals(65538, read[0]);
        assertTrue(output.size() <= 65537);
    }

    @Test public void supportsLongLimitWithoutOverflowAndPropagatesProducerFailure() throws Exception {
        assertEquals(1, BoundedCopy.copy(new ByteArrayInputStream(new byte[1]), new ByteArrayOutputStream(), Long.MAX_VALUE));
        InputStream failed = new InputStream() { @Override public int read() throws IOException { throw new IOException("provider failed"); } };
        try { BoundedCopy.copy(failed, new ByteArrayOutputStream(), 64); fail("Provider failure lost"); }
        catch (IOException expected) { assertEquals("provider failed", expected.getMessage()); }
    }
}
