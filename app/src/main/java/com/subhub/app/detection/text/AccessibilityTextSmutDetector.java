package com.subhub.app.detection.text;

import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;

import com.subhub.app.detection.Detection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded extraction of visible native app text from a user-enabled Accessibility session. */
public final class AccessibilityTextSmutDetector {
    private static final int MAX_NODES = 900;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final int PREFETCH_FLAGS =
            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST;
    private final TextSmutDetectionFactory factory;

    public AccessibilityTextSmutDetector() {
        this(new SmutTextClassifier());
    }

    public AccessibilityTextSmutDetector(SmutTextClassifier classifier) {
        factory = new TextSmutDetectionFactory(classifier);
    }

    public List<Detection> detect(
            AccessibilityNodeInfo root,
            TextSmutConfig config,
            int width,
            int height) {
        return detect(root, config, width, height, false, false);
    }

    public List<Detection> detect(
            AccessibilityNodeInfo root,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled) {
        return detect(root, config, width, height, semanticEnabled, false);
    }

    public List<Detection> detect(
            AccessibilityNodeInfo root,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled,
            boolean exactGeometryPreferred) {
        return detect(root, config, width, height, semanticEnabled,
                exactGeometryPreferred, () -> false);
    }

    /** Traverses visible text while allowing a newly started scroll to cancel obsolete work. */
    public List<Detection> detect(
            AccessibilityNodeInfo root,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled,
            boolean exactGeometryPreferred,
            BooleanSupplier cancelled) {
        ScanResult scan = detectWithMetrics(root, config, width, height, semanticEnabled,
                exactGeometryPreferred, cancelled);
        try {
            return scan.getDetections();
        } finally {
            scan.close();
        }
    }

    /** Returns traversal cost as well as detections so device traces can expose Binder/tree work. */
    public ScanResult detectWithMetrics(
            AccessibilityNodeInfo root,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled,
            boolean exactGeometryPreferred,
            BooleanSupplier cancelled) {
        if (root == null || config == null || !config.isEnabled()) return ScanResult.EMPTY;
        List<Detection> result = new ArrayList<>();
        List<ConfirmationProbe> confirmationProbes = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        int textNodes = 0;
        int classifiedNodes = 0;
        boolean wasCancelled = false;
        while (!pending.isEmpty() && visited < MAX_NODES) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                wasCancelled = true;
                break;
            }
            AccessibilityNodeInfo node = pending.removeFirst();
            visited++;
            boolean ownsNode = node != root;
            try {
                if (!node.isVisibleToUser()) continue;
                String text = textOf(node);
                if (!text.isEmpty()) {
                    textNodes++;
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (isUsefulTextRegion(node, bounds, width, height)) {
                        classifiedNodes++;
                        SmutTextClassifier.Match match = factory.classify(
                                text, config, semanticEnabled);
                        // Character-location extras cross the Accessibility Binder boundary and
                        // can cost several milliseconds per node. Safe text needs no geometry at
                        // all, so only ask Android for exact character bounds after classification
                        // has produced a real censor candidate.
                        if (!match.isMatched()) continue;
                        Rect characterBounds = AccessibilityTextGeometry.resolve(
                                node, text, match, width, height);
                        if (characterBounds == null
                                && (!isPlausibleTextRegion(text, bounds, width)
                                || (exactGeometryPreferred
                                && !isTightEstimatedRegion(bounds, width)))) continue;
                        Detection detection = characterBounds == null
                                ? factory.create(text, bounds, match, width, height,
                                        "TEXT_SMUT_ACCESSIBILITY_")
                                : factory.createExact(characterBounds, match, width, height,
                                        "TEXT_SMUT_ACCESSIBILITY_");
                        if (detection != null) {
                            detection = detection.withObservation(
                                    Detection.ObservationSource.ACCESSIBILITY,
                                    characterBounds == null
                                            ? Detection.GeometryQuality.ESTIMATED
                                            : Detection.GeometryQuality.EXACT,
                                    anchorFor(node, text));
                            result.add(detection);
                            AccessibilityNodeInfo probeNode = copyNode(node);
                            if (probeNode != null) {
                                confirmationProbes.add(new ConfirmationProbe(
                                        probeNode, detection.getAnchorKey()));
                            }
                        }
                    }
                }
                for (int index = 0; index < node.getChildCount(); index++) {
                    AccessibilityNodeInfo child = childWithPrefetch(node, index);
                    if (child != null) pending.addLast(child);
                }
            } finally {
                if (ownsNode) node.recycle();
            }
        }
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo abandoned = pending.removeFirst();
            if (abandoned != root) abandoned.recycle();
        }
        return new ScanResult(Collections.unmodifiableList(
                DetectionFusion.merge(Collections.emptyList(), result)),
                confirmationProbes, visited, textNodes, classifiedNodes, wasCancelled);
    }

    /** Revalidates only nodes that matched in the full scan, avoiding a second tree traversal. */
    public ConfirmResult confirmCandidates(
            ScanResult source,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled,
            boolean exactGeometryPreferred,
            BooleanSupplier cancelled) {
        if (source == null || config == null || !config.isEnabled()) {
            if (source != null) source.close();
            return ConfirmResult.EMPTY;
        }
        List<ConfirmationProbe> probes = source.takeProbes();
        if (probes.isEmpty()) return ConfirmResult.EMPTY;
        List<Detection> confirmed = new ArrayList<>(probes.size());
        int attempted = 0;
        int refreshed = 0;
        int processed = 0;
        boolean wasCancelled = false;
        try {
            for (ConfirmationProbe probe : probes) {
                processed++;
                try {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        wasCancelled = true;
                        break;
                    }
                    attempted++;
                    AccessibilityNodeInfo node = probe.node;
                    if (!node.refresh()) continue;
                    refreshed++;
                    Detection detection = detectionForNode(node, config, width, height,
                            semanticEnabled, exactGeometryPreferred);
                    if (detection != null && probe.anchorKey.equals(detection.getAnchorKey())) {
                        confirmed.add(detection);
                    }
                } finally {
                    probe.node.recycle();
                }
            }
        } finally {
            for (int index = processed; index < probes.size(); index++) {
                probes.get(index).node.recycle();
            }
        }
        return new ConfirmResult(Collections.unmodifiableList(
                DetectionFusion.merge(Collections.emptyList(), confirmed)),
                attempted, refreshed, confirmed.size(), wasCancelled);
    }

    public static final class ScanResult {
        private static final ScanResult EMPTY = new ScanResult(
                Collections.emptyList(), Collections.emptyList(), 0, 0, 0, false);

        private final List<Detection> detections;
        private List<ConfirmationProbe> confirmationProbes;
        private final int visitedNodes;
        private final int textNodes;
        private final int classifiedNodes;
        private final boolean cancelled;

        private ScanResult(
                List<Detection> detections,
                List<ConfirmationProbe> confirmationProbes,
                int visitedNodes,
                int textNodes,
                int classifiedNodes,
                boolean cancelled) {
            this.detections = detections;
            this.confirmationProbes = confirmationProbes;
            this.visitedNodes = visitedNodes;
            this.textNodes = textNodes;
            this.classifiedNodes = classifiedNodes;
            this.cancelled = cancelled;
        }

        public List<Detection> getDetections() { return detections; }
        public int getVisitedNodes() { return visitedNodes; }
        public int getTextNodes() { return textNodes; }
        public int getClassifiedNodes() { return classifiedNodes; }
        public boolean isCancelled() { return cancelled; }
        public synchronized int getConfirmationProbeCount() {
            return confirmationProbes.size();
        }

        private synchronized List<ConfirmationProbe> takeProbes() {
            if (confirmationProbes.isEmpty()) return Collections.emptyList();
            List<ConfirmationProbe> result = confirmationProbes;
            confirmationProbes = Collections.emptyList();
            return result;
        }

        public void close() {
            for (ConfirmationProbe probe : takeProbes()) probe.node.recycle();
        }
    }

    public static final class ConfirmResult {
        private static final ConfirmResult EMPTY = new ConfirmResult(
                Collections.emptyList(), 0, 0, 0, false);
        private final List<Detection> detections;
        private final int attemptedNodes;
        private final int refreshedNodes;
        private final int confirmedNodes;
        private final boolean cancelled;

        private ConfirmResult(
                List<Detection> detections,
                int attemptedNodes,
                int refreshedNodes,
                int confirmedNodes,
                boolean cancelled) {
            this.detections = detections;
            this.attemptedNodes = attemptedNodes;
            this.refreshedNodes = refreshedNodes;
            this.confirmedNodes = confirmedNodes;
            this.cancelled = cancelled;
        }

        public List<Detection> getDetections() { return detections; }
        public int getAttemptedNodes() { return attemptedNodes; }
        public int getRefreshedNodes() { return refreshedNodes; }
        public int getConfirmedNodes() { return confirmedNodes; }
        public boolean isCancelled() { return cancelled; }
    }

    private Detection detectionForNode(
            AccessibilityNodeInfo node,
            TextSmutConfig config,
            int width,
            int height,
            boolean semanticEnabled,
            boolean exactGeometryPreferred) {
        if (node == null || !node.isVisibleToUser()) return null;
        String text = textOf(node);
        if (text.isEmpty()) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!isUsefulTextRegion(node, bounds, width, height)) return null;
        SmutTextClassifier.Match match = factory.classify(text, config, semanticEnabled);
        if (!match.isMatched()) return null;
        Rect characterBounds = AccessibilityTextGeometry.resolve(
                node, text, match, width, height);
        if (characterBounds == null
                && (!isPlausibleTextRegion(text, bounds, width)
                || (exactGeometryPreferred && !isTightEstimatedRegion(bounds, width)))) {
            return null;
        }
        Detection detection = characterBounds == null
                ? factory.create(text, bounds, match, width, height,
                        "TEXT_SMUT_ACCESSIBILITY_")
                : factory.createExact(characterBounds, match, width, height,
                        "TEXT_SMUT_ACCESSIBILITY_");
        if (detection == null) return null;
        return detection.withObservation(
                Detection.ObservationSource.ACCESSIBILITY,
                characterBounds == null
                        ? Detection.GeometryQuality.ESTIMATED
                        : Detection.GeometryQuality.EXACT,
                anchorFor(node, text));
    }

    private static AccessibilityNodeInfo copyNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new AccessibilityNodeInfo(node);
        }
        //noinspection deprecation
        return AccessibilityNodeInfo.obtain(node);
    }

    private static final class ConfirmationProbe {
        private final AccessibilityNodeInfo node;
        private final String anchorKey;

        private ConfirmationProbe(AccessibilityNodeInfo node, String anchorKey) {
            this.node = node;
            this.anchorKey = anchorKey;
        }
    }

    private static boolean isUsefulTextRegion(
            AccessibilityNodeInfo node, Rect bounds, int width, int height) {
        if (bounds.isEmpty()) return false;
        long area = (long) bounds.width() * bounds.height();
        long screen = (long) Math.max(1, width) * Math.max(1, height);
        // Aggregate root/window descriptions duplicate descendant text and create screen-sized
        // censors. Post-sized containers remain eligible; the projector reduces them to lines.
        return node.getChildCount() == 0 || area * 100L < screen * 58L;
    }

    /** Rejects long container descriptions attached to tiny icons and action controls. */
    private static boolean isPlausibleTextRegion(String text, Rect bounds, int screenWidth) {
        int normalizedLength = SmutTextClassifier.normalize(text).length();
        int lineHeight = Math.max(24, Math.round(screenWidth * 0.035f));
        int estimatedLines = Math.max(1,
                (bounds.height() + lineHeight - 1) / lineHeight);
        int charactersPerLine = Math.max(4,
                Math.round(bounds.width() / Math.max(8f, lineHeight * 0.48f)));
        long estimatedCapacity = (long) estimatedLines * charactersPerLine * 2L;
        return normalizedLength <= estimatedCapacity;
    }

    /** Ultra OCR owns post/image-sized containers; Accessibility keeps only tight text nodes. */
    private static boolean isTightEstimatedRegion(Rect bounds, int screenWidth) {
        return bounds.height() <= Math.max(120, Math.round(screenWidth * 0.24f));
    }

    private static String textOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        String first = text == null ? "" : text.toString().trim();
        String second = description == null ? "" : description.toString().trim();
        // Some Compose/social feeds expose a caption only as a container description. Keep that
        // fallback: the region projector bounds it to text lines and fusion prefers tighter child
        // nodes whenever the same phrase is also exposed below this container.
        if (!first.isEmpty() && node.getChildCount() > 0) second = "";
        String combined = first;
        if (!second.isEmpty() && !second.equalsIgnoreCase(first)) {
            combined = first.isEmpty() ? second : first + " " + second;
        }
        return combined.length() <= MAX_TEXT_LENGTH
                ? combined : combined.substring(0, MAX_TEXT_LENGTH);
    }

    private static String anchorFor(AccessibilityNodeInfo node, String text) {
        String normalized = SmutTextClassifier.normalize(text);
        String textIdentity = Integer.toUnsignedString(normalized.hashCode(), 36);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            String uniqueId = node.getUniqueId();
            if (uniqueId != null && !uniqueId.isEmpty()) {
                // Recycler-backed views may keep a node identity while binding different text.
                // Include content identity so a recycled cell cannot inherit confirmation.
                return "a11y:id:" + uniqueId + ':' + textIdentity;
            }
        }
        String viewId = node.getViewIdResourceName();
        String className = node.getClassName() == null ? "" : node.getClassName().toString();
        return "a11y:text:" + (viewId == null ? "" : viewId) + ':' + className + ':'
                + textIdentity;
    }

    private static AccessibilityNodeInfo childWithPrefetch(
            AccessibilityNodeInfo node,
            int index) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return node.getChild(index, PREFETCH_FLAGS);
        }
        return node.getChild(index);
    }
}
