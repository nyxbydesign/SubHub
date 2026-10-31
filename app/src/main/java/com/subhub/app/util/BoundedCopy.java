package com.subhub.app.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Counts actual bytes even when a document provider omits or misreports its size. */
public final class BoundedCopy {
    private BoundedCopy() { }
    public static long copy(InputStream input, OutputStream output, long maximum) throws IOException {
        if (input == null) throw new IOException("Source is unavailable");
        if (maximum < 0) throw new IllegalArgumentException("Invalid byte limit");
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer, 0,
                maximum - total < buffer.length ? (int) (maximum - total + 1) : buffer.length)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Import cancelled");
            if (count > maximum - total) throw new IOException("File exceeds the size limit");
            output.write(buffer, 0, count);
            total += count;
        }
        return total;
    }
}
