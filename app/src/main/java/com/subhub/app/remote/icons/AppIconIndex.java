package com.subhub.app.remote.icons;

import com.subhub.app.util.RemoteAppIcon;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconstructible metadata keyed by hashes of the exact peer, generation and package. */
final class AppIconIndex {
    // Keep a bounded history of unavailable offers alongside the complete PNGs.
    static final int MAX_ENTRIES = 2 * AppIconCache.MAX_ENTRIES;
    private static final int MAX_BYTES = 80 * 1024;
    static final int WRITE_RESERVE = 2 * MAX_BYTES;
    private final File root;

    AppIconIndex(File privateCacheDirectory) throws IOException {
        root = new File(privateCacheDirectory.getCanonicalFile(), "remote-app-icon-index-v1");
    }

    Map<String, RemoteAppIcon.Descriptor> read(String scope) throws IOException {
        File file = file(scope + ".idx");
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        long length = file.length();
        if (length > MAX_BYTES) throw new IOException("Oversized icon index");
        byte[] bytes = AppIconCache.read(file, (int) length);
        if (bytes == null) throw new IOException("Icon index size changed");
        Map<String, RemoteAppIcon.Descriptor> entries = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t");
            if ((fields.length != 1 && fields.length != 5) || !fields[0].matches("[0-9a-f]{64}")
                    || entries.containsKey(fields[0]) || entries.size() == MAX_ENTRIES)
                throw new IOException("Invalid icon index entry");
            try {
                // A bare offer is also recorded so a previously cached icon stays retired.
                entries.put(fields[0], fields.length == 1 ? null : new RemoteAppIcon.Descriptor(
                        fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Integer.parseInt(fields[4])));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid cached descriptor", invalid);
            }
        }
        return entries;
    }

    void write(String scope, Map<String, RemoteAppIcon.Descriptor> entries) throws IOException {
        File file = file(scope + ".idx"), pending = file(scope + ".part");
        Files.createDirectories(root.toPath());
        StringBuilder data = new StringBuilder();
        entries.forEach((key, value) -> {
            data.append(key);
            if (value != null) data.append('\t').append(value.sha256).append('\t').append(value.byteLength)
                    .append('\t').append(value.width).append('\t').append(value.height);
            data.append('\n');
        });
        try {
            Files.write(pending.toPath(), data.toString().getBytes(StandardCharsets.US_ASCII));
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }

    private File file(String name) throws IOException {
        File file = new File(root, name);
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(file.toPath()))
            throw new IOException("Unsafe icon index");
        return file;
    }
}
