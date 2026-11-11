package com.subhub.app.popup;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** A bounded content-hash allowlist, never a directory, URI, device pack, or executable authority. */
final class PopupArtworkManifest {
    static final int MAX_IMAGES = 512;
    static final int MAX_DECODE_EDGE = 1280;
    static final long MAX_DECODE_BYTES = 24L * 1024 * 1024;
    final Set<String> enabled;
    final boolean builtInFallback;

    PopupArtworkManifest(Set<String> included, Set<String> disabled) {
        Objects.requireNonNull(included);
        Objects.requireNonNull(disabled);
        if (included.size() > MAX_IMAGES || disabled.size() > MAX_IMAGES)
            throw new IllegalArgumentException("Too many owned popup images");
        Set<String> copy = new LinkedHashSet<>();
        for (String hash : included) {
            requireHash(hash);
            if (!disabled.contains(hash)) copy.add(hash);
        }
        for (String hash : disabled) requireHash(hash);
        enabled = Collections.unmodifiableSet(copy);
        // The fixed APK guardian is the ordinary empty-library default, never disabled user art.
        builtInFallback = enabled.isEmpty();
    }
    /** Every enabled image remains eligible; lower resolution, never a silent prefix truncation. */
    int decodeEdge() {
        long perImageBytes = MAX_DECODE_BYTES / Math.max(1, enabled.size());
        return Math.min(MAX_DECODE_EDGE, Math.max(1, (int) Math.sqrt(perImageBytes / 4d) - 2));
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid owned popup hash");
    }
}
