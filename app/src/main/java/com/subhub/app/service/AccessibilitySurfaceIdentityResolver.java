package com.subhub.app.service;

import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Resolves the scroll owner of an Accessibility event without retaining Accessibility content.
 *
 * <p>The event source is frequently a recycled child (especially in a RecyclerView or a Chrome
 * WebView accessibility tree), so it is deliberately not used as the surface identity. The
 * nearest scrollable ancestor owns the identity. Only hashed class/ID material crosses the
 * resolver boundary; text, descriptions, bounds, offsets, indexes, timestamps, and raw IDs never
 * leave this class.</p>
 */
final class AccessibilitySurfaceIdentityResolver {
    static final int MAX_PARENT_HOPS = 6;

    static final byte OWNER_NONE = 0;
    static final byte OWNER_UNIQUE_ID = 1;
    static final byte OWNER_VIEW_ID = 2;
    static final byte OWNER_STRUCTURAL = 3;

    static final byte CONFIDENCE_LOW = 0;
    static final byte CONFIDENCE_MEDIUM = 1;
    static final byte CONFIDENCE_HIGH = 2;

    private static final long HASH_SEED_LO = randomSeed(0x9E3779B97F4A7C15L);
    private static final long HASH_SEED_HI = randomSeed(0xD1B54A32D192ED03L);

    /* A resolver is used on the Accessibility callback thread. Reused scratch avoids per-event
     * arrays while synchronization makes the helper also safe for deterministic tests. */
    private final long[] classHashesLo = new long[MAX_PARENT_HOPS + 1];
    private final long[] classHashesHi = new long[MAX_PARENT_HOPS + 1];
    private final long[] stableIdHashesLo = new long[MAX_PARENT_HOPS + 1];
    private final long[] stableIdHashesHi = new long[MAX_PARENT_HOPS + 1];
    private final byte[] stableIdKinds = new byte[MAX_PARENT_HOPS + 1];
    private final boolean[] scrollable = new boolean[MAX_PARENT_HOPS + 1];

    /** Resolves an event and recycles the source and every parent obtained during the walk. */
    synchronized Identity resolve(AccessibilityEvent event) {
        if (event == null) return Identity.empty();
        String packageName = null;
        try {
            CharSequence value = event.getPackageName();
            packageName = value == null ? null : value.toString();
        } catch (RuntimeException ignored) {
            // A transiently invalid Accessibility parcel is an explicitly non-cacheable result.
        }
        int windowId;
        try {
            windowId = event.getWindowId();
        } catch (RuntimeException ignored) {
            windowId = -1;
        }
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
        } catch (RuntimeException ignored) {
            // Keep the low-confidence event fallback below.
        }
        return resolveForNode(windowId, packageName,
                source == null ? null : new AndroidNode(source));
    }

    /**
     * Package-private adapter seam for the service and deterministic tests. Ownership transfers to
     * this method: {@code source} and all parents returned by {@link Node#parent()} are closed.
     */
    synchronized Identity resolveForNode(int windowId, String packageName, Node source) {
        return resolveForNode(windowId, packageName, source, null);
    }

    /** Worker-only learning path; resource verification must never perform I/O in callbacks. */
    synchronized Identity resolveForNode(int windowId, String packageName, Node source,
            ResourceVerifier verifier) {
        clearScratch();
        String[] structuralParts = verifier == null ? null : new String[MAX_PARENT_HOPS + 1];
        boolean[] compiledIds = verifier == null ? null : new boolean[MAX_PARENT_HOPS + 1];
        int count = 0;
        Node current = source;
        boolean traversalFailed = false;
        boolean reachedRoot = false;
        try {
            while (current != null && count <= MAX_PARENT_HOPS) {
                String className = current.className();
                classHashesLo[count] = hashString(className, HASH_SEED_LO);
                classHashesHi[count] = hashString(className, HASH_SEED_HI);
                // The Android adapter gates this call by API level. Keeping the generic seam
                // unguarded makes it usable by deterministic in-process node adapters too.
                String uniqueId = current.uniqueId();
                String viewId = current.viewId();
                if (verifier != null) {
                    // A resource-shaped DOM id is not proof of a compiled Android resource.
                    // Only the worker's package Resources adapter may establish that proof.
                    boolean compiled = resourceShape(viewId, packageName)
                            && verifier.isCompiledResource(viewId);
                    compiledIds[count] = compiled;
                    if (classShape(className) && !className.contains("WebView")
                            && (viewId == null || viewId.isEmpty() || compiled)) {
                        structuralParts[count] = className + "|" + (compiled ? viewId : "")
                                + "|" + current.isScrollable();
                    }
                }
                if (uniqueId != null && !uniqueId.isEmpty()) {
                    stableIdKinds[count] = OWNER_UNIQUE_ID;
                    stableIdHashesLo[count] = hashString(uniqueId, HASH_SEED_LO);
                    stableIdHashesHi[count] = hashString(uniqueId, HASH_SEED_HI);
                } else if (viewId != null && !viewId.isEmpty()) {
                    stableIdKinds[count] = OWNER_VIEW_ID;
                    stableIdHashesLo[count] = hashString(viewId, HASH_SEED_LO);
                    stableIdHashesHi[count] = hashString(viewId, HASH_SEED_HI);
                } else {
                    stableIdKinds[count] = OWNER_NONE;
                    stableIdHashesLo[count] = 0L;
                    stableIdHashesHi[count] = 0L;
                }
                scrollable[count] = current.isScrollable();
                count++;
                if (count > MAX_PARENT_HOPS) break;
                Node next = current.parent();
                close(current);
                reachedRoot = next == null;
                current = next == current ? null : next;
            }
        } catch (RuntimeException ignored) {
            // Broken provider trees must never turn an unstable source into reusable cache state.
            traversalFailed = true;
        } finally {
            close(current);
        }

        long packageLo = hashString(packageName, HASH_SEED_LO);
        long packageHi = hashString(packageName, HASH_SEED_HI);
        int ownerDepth = firstScrollable(count);
        if (ownerDepth < 0) {
            long fallbackLo = mix(packageLo ^ windowId, classHashesLo[0]);
            long fallbackHi = mix(packageHi ^ windowId, classHashesHi[0]);
            return new Identity(windowId, fallbackHi, fallbackLo,
                    0L, 0L, OWNER_NONE, CONFIDENCE_LOW, false, null,
                    source != null, count, -1, traversalFailed);
        }

        byte ownerKind = stableIdKinds[ownerDepth];
        byte confidence = ownerKind == OWNER_UNIQUE_ID ? CONFIDENCE_HIGH
                : ownerKind == OWNER_VIEW_ID ? CONFIDENCE_MEDIUM : CONFIDENCE_LOW;
        long pathLo = HASH_SEED_LO ^ 0xA24BAED4963EE407L;
        long pathHi = HASH_SEED_HI ^ 0x3C79AC492BA7B653L;
        for (int index = ownerDepth; index < count; index++) {
            pathLo = mix(pathLo, classHashesLo[index]);
            pathLo = mix(pathLo, stableIdHashesLo[index]);
            pathLo = mix(pathLo, stableIdKinds[index]);
            pathHi = mix(pathHi, classHashesHi[index]);
            pathHi = mix(pathHi, stableIdHashesHi[index]);
            pathHi = mix(pathHi, stableIdKinds[index]);
        }
        long ownerLo = stableIdHashesLo[ownerDepth];
        long ownerHi = stableIdHashesHi[ownerDepth];
        long tokenLo = mix(mix(packageLo, windowId), ownerLo);
        tokenLo = mix(tokenLo, classHashesLo[ownerDepth]);
        tokenLo = mix(tokenLo, pathLo);
        long tokenHi = mix(mix(packageHi, windowId), ownerHi);
        tokenHi = mix(tokenHi, classHashesHi[ownerDepth]);
        tokenHi = mix(tokenHi, pathHi);
        boolean knownPackage = packageName != null && !packageName.isEmpty();
        boolean stableWindow = windowId >= 0;
        if (traversalFailed || !knownPackage || !stableWindow) confidence = CONFIDENCE_LOW;
        boolean cacheable = !traversalFailed && knownPackage && stableWindow
                && (ownerKind == OWNER_UNIQUE_ID || ownerKind == OWNER_VIEW_ID);
        String durableDigest = null;
        if (verifier != null && cacheable && reachedRoot && compiledIds[ownerDepth]) {
            durableDigest = structuralDigest(packageName, structuralParts, ownerDepth, count);
        }
        return new Identity(windowId, tokenHi, tokenLo, ownerHi, ownerLo,
                ownerKind == OWNER_NONE ? OWNER_STRUCTURAL : ownerKind,
                confidence, cacheable, durableDigest, source != null, count, ownerDepth, traversalFailed);
    }

    interface ResourceVerifier { boolean isCompiledResource(String resourceName); }

    private static boolean resourceShape(String value, String packageName) {
        if (value == null || value.length() > 300 || packageName == null) return false;
        String prefix = packageName + ":id/";
        if (!value.startsWith(prefix) && !value.startsWith("android:id/")) return false;
        return value.substring(value.indexOf('/') + 1).matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }

    private static boolean classShape(String value) {
        return value != null && value.length() <= 255
                && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    private static String structuralDigest(String packageName, String[] parts, int start, int end) {
        StringBuilder canonical = new StringBuilder("scroll-surface-v1|").append(packageName);
        for (int index = start; index < end; index++) {
            if (parts[index] == null) return null;
            canonical.append('\n').append(parts[index]);
        }
        return digest(canonical.toString());
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[bytes.length * 2];
            final String digits = "0123456789abcdef";
            for (int index = 0; index < bytes.length; index++) {
                hex[index * 2] = digits.charAt((bytes[index] >>> 4) & 15);
                hex[index * 2 + 1] = digits.charAt(bytes[index] & 15);
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private int firstScrollable(int count) {
        for (int index = 0; index < count; index++) {
            if (scrollable[index]) return index;
        }
        return -1;
    }

    private void clearScratch() {
        for (int index = 0; index <= MAX_PARENT_HOPS; index++) {
            classHashesLo[index] = 0L;
            classHashesHi[index] = 0L;
            stableIdHashesLo[index] = 0L;
            stableIdHashesHi[index] = 0L;
            stableIdKinds[index] = OWNER_NONE;
            scrollable[index] = false;
        }
    }

    private static void close(Node node) {
        if (node != null) node.close();
    }

    private static long randomSeed(long fallback) {
        try {
            return new SecureRandom().nextLong() ^ fallback;
        } catch (RuntimeException ignored) {
            return System.nanoTime() ^ fallback;
        }
    }

    private static long hashString(String value, long seed) {
        long hash = seed ^ 0xCBF29CE484222325L;
        if (value == null) return mix(hash, 0L);
        hash = mix(hash, value.length());
        for (int index = 0; index < value.length(); index++) {
            hash = mix(hash, value.charAt(index));
        }
        return hash;
    }

    private static long mix(long first, long second) {
        long value = first ^ (second + 0x9E3779B97F4A7C15L
                + (first << 6) + (first >>> 2));
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** Ephemeral node view; implementations must not expose text or content descriptions. */
    interface Node {
        boolean isScrollable();
        String uniqueId();
        String viewId();
        String className();
        Node parent();
        void close();
    }

    /** Primitive, privacy-safe identity retained by the service/cache. */
    static final class Identity {
        private static final Identity EMPTY = new Identity(
                -1, 0L, 0L, 0L, 0L, OWNER_NONE, CONFIDENCE_LOW, false);

        final int windowId;
        final long tokenHi;
        final long tokenLo;
        final long ownerHi;
        final long ownerLo;
        final byte ownerKind;
        final byte confidence;
        final boolean cacheable;
        // A prior-candidate key only. Every runtime producer still needs independent validation.
        final String durableDigest;
        final boolean sourcePresent, traversalFailed;
        final int nodeCount, ownerDepth;

        private Identity(
                int windowId,
                long tokenHi,
                long tokenLo,
                long ownerHi,
                long ownerLo,
                byte ownerKind,
                byte confidence,
                boolean cacheable) {
            this(windowId, tokenHi, tokenLo, ownerHi, ownerLo, ownerKind, confidence, cacheable, null);
        }

        private Identity(int windowId, long tokenHi, long tokenLo, long ownerHi, long ownerLo,
                byte ownerKind, byte confidence, boolean cacheable, String durableDigest) {
            this(windowId, tokenHi, tokenLo, ownerHi, ownerLo, ownerKind, confidence,
                    cacheable, durableDigest, false, 0, -1, false);
        }

        private Identity(int windowId, long tokenHi, long tokenLo, long ownerHi, long ownerLo,
                byte ownerKind, byte confidence, boolean cacheable, String durableDigest,
                boolean sourcePresent, int nodeCount, int ownerDepth, boolean traversalFailed) {
            this.windowId = windowId;
            this.tokenHi = tokenHi;
            this.tokenLo = tokenLo;
            this.ownerHi = ownerHi;
            this.ownerLo = ownerLo;
            this.ownerKind = ownerKind;
            this.confidence = confidence;
            this.cacheable = cacheable;
            this.durableDigest = durableDigest;
            this.sourcePresent = sourcePresent;
            this.nodeCount = nodeCount;
            this.ownerDepth = ownerDepth;
            this.traversalFailed = traversalFailed;
        }

        static Identity empty() { return EMPTY; }

        boolean isCacheable() { return cacheable; }
        boolean isLowConfidence() { return confidence == CONFIDENCE_LOW; }

        /** Unknown structures are process/window-local and must never be persisted. */
        String learningDigest() {
            return durableDigest != null ? durableDigest
                    : digest("scroll-session-v1|" + tokenHi + "|" + tokenLo + "|" + windowId);
        }

        /** A salted token suitable for allowlisted telemetry; it is not a raw identifier. */
        long telemetryToken() {
            return mix(tokenLo ^ HASH_SEED_LO, tokenHi ^ HASH_SEED_HI);
        }

        boolean sameSurface(Identity other) {
            return other != null
                    && windowId == other.windowId
                    && ownerKind == other.ownerKind
                    && tokenHi == other.tokenHi
                    && tokenLo == other.tokenLo;
        }

        @Override
        public String toString() {
            return "surface:" + Long.toUnsignedString(telemetryToken(), 16)
                    + ":confidence=" + confidence + ":cacheable=" + cacheable;
        }
    }

    private static final class AndroidNode implements Node {
        private AccessibilityNodeInfo node;

        private AndroidNode(AccessibilityNodeInfo node) {
            this.node = node;
        }

        @Override
        public boolean isScrollable() {
            return node != null && node.isScrollable();
        }

        @Override
        public String uniqueId() {
            if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null;
            return node.getUniqueId();
        }

        @Override
        public String viewId() {
            return node == null ? null : node.getViewIdResourceName();
        }

        @Override
        public String className() {
            if (node == null || node.getClassName() == null) return null;
            return node.getClassName().toString();
        }

        @Override
        public Node parent() {
            if (node == null) return null;
            AccessibilityNodeInfo parent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                parent = node.getParent(AccessibilityNodeInfo.FLAG_PREFETCH_ANCESTORS);
            } else {
                parent = node.getParent();
            }
            return parent == null ? null : new AndroidNode(parent);
        }

        @Override
        public void close() {
            AccessibilityNodeInfo value = node;
            node = null;
            if (value != null) value.recycle();
        }
    }
}
