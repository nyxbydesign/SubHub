package com.subhub.app.runtime;

import java.io.File;
import java.io.IOException;

/** Fail-closed directory flush protocol, separate from its Android descriptor adapter. */
final class RuntimeOwnerDirectorySync {
    interface Descriptor extends AutoCloseable {
        int flags() throws IOException;
        void flags(int value) throws IOException;
        boolean isDirectory() throws IOException;
        void sync() throws IOException;
        @Override void close() throws IOException;
    }
    interface Opener { Descriptor open(File directory) throws IOException; }

    private RuntimeOwnerDirectorySync() { }

    static void sync(File directory, Opener opener, int closeOnExec) throws IOException {
        try (Descriptor descriptor = opener.open(directory)) {
            descriptor.flags(descriptor.flags() | closeOnExec);
            if ((descriptor.flags() & closeOnExec) == 0)
                throw new IOException("Owner directory close-on-exec was not acknowledged");
            // Check the actual descriptor, not a pathname that could identify another inode.
            if (!descriptor.isDirectory())
                throw new IOException("Owner synchronization target is not a directory");
            descriptor.sync();
        }
    }
}
