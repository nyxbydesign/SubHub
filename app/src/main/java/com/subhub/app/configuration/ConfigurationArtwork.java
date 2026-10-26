package com.subhub.app.configuration;

/** Relative artwork paths accepted by the configuration protocol. */
public final class ConfigurationArtwork {
    private ConfigurationArtwork() { }

    public static boolean isSafeAssetPath(String path) {
        if (path == null) return false;
        String value = path.replace('\\', '/');
        return isSafeEntryPath(value) && (value.startsWith("assets/censor/")
                || value.startsWith("assets/popup/") || value.startsWith("assets/cover/"));
    }

    private static boolean isSafeEntryPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains(":")
                || path.indexOf('\u0000') >= 0 || path.contains("\\")) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }
}
