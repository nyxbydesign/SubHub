package com.subhub.app.service;

import java.util.ArrayDeque;

/**
 * Timestamped vertical camera accounting for visual measurements and delayed scroll events.
 * This component owns no tracker, cache, or renderer. Callers must validate visual evidence and
 * reset it on document/window/capture-scope changes before granting its positions authority.
 */
final class VisualScrollReconciler {
    private static final int MAX_EVENTS = 192;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private double eventY;
    private double anchorEventY;
    private double anchorVisualY;
    private long anchorTime = -1L;
    private long discardedThrough = -1L;
    private long frameTime = -1L;
    private double frameY;
    private boolean frameMeasured;

    void reset(double initialY) {
        if (!Double.isFinite(initialY)) throw new IllegalArgumentException("Non-finite origin");
        events.clear();
        eventY = initialY;
        anchorEventY = initialY;
        anchorVisualY = initialY;
        anchorTime = discardedThrough = frameTime = -1L;
        frameY = initialY;
        frameMeasured = false;
    }

    double cameraY() {
        return anchorTime < 0L ? eventY : anchorVisualY + eventY - anchorEventY;
    }

    /**
     * Returns the known camera change. A delta spanning the visual anchor cannot be split exactly
     * from timestamps alone: withhold it and expose uncertainty until a later visual anchor
     * covers it. The caller must use the previous event from the SAME producer as intervalStart,
     * or zero for an unknown start; receipt time is never a substitute for source time.
     */
    double recordEvent(long intervalStart, long effectiveTime, int contentDy) {
        if (intervalStart < 0L || effectiveTime < intervalStart) {
            throw new IllegalArgumentException("Invalid event interval");
        }
        double before = cameraY();
        eventY += contentDy;
        if (anchorTime >= 0L && (effectiveTime <= anchorTime || intervalStart < anchorTime)) {
            anchorEventY += contentDy;
        }
        events.addLast(new Event(intervalStart, effectiveTime, contentDy));
        while (events.size() > MAX_EVENTS) {
            discardedThrough = Math.max(discardedThrough, events.removeFirst().time);
        }
        return cameraY() - before;
    }

    boolean hasUnresolvedEventInterval() {
        return anchorTime >= 0L
                && (discardedThrough > anchorTime || crossesInterval(anchorTime));
    }

    /**
     * Observes every frame, including rejected pairs. screenDy is current minus previous image
     * displacement; content/camera coordinates have the opposite sign. Events newer than the
     * current screenshot remain a tail, even if they arrived before the visual result.
     */
    Measurement observe(long previousTime, long currentTime, double screenDy, boolean accepted) {
        double before = cameraY();
        if (currentTime < 0L || currentTime <= frameTime || !Double.isFinite(screenDy)) {
            return new Measurement(false, before, 0d);
        }
        boolean compatible = accepted && previousTime >= 0L && previousTime == frameTime
                && previousTime > discardedThrough && currentTime > previousTime
                && (frameMeasured || !crossesInterval(previousTime));
        if (compatible) {
            double previousY = frameMeasured ? frameY : positionAt(previousTime);
            frameY = previousY - screenDy;
            anchorVisualY = frameY;
            anchorEventY = eventsAt(currentTime);
            anchorTime = currentTime;
            frameMeasured = true;
        } else {
            frameY = positionAt(currentTime);
            // The first image defines an arbitrary but fixed origin for relative visual motion.
            // Later event delivery must not rewrite that origin. After a broken/rejected pair,
            // however, the event-derived bridge is not a new visual measurement.
            frameMeasured = frameTime < 0L;
        }
        frameTime = currentTime;
        return new Measurement(compatible, frameY, cameraY() - before);
    }

    private double positionAt(long time) {
        double value = eventsAt(time);
        return anchorTime < 0L ? value : anchorVisualY + value - anchorEventY;
    }

    private double eventsAt(long time) {
        double value = eventY;
        // Include a crossing interval in the baseline so NONE of its unknown tail gets added
        // after the anchor. hasUnresolvedEventInterval prevents treating this as exact motion.
        for (Event event : events) {
            if (event.time > time && event.start >= time) value -= event.dy;
        }
        return value;
    }

    private boolean crossesInterval(long time) {
        for (Event event : events) if (event.start < time && event.time > time) return true;
        return false;
    }

    static final class Measurement {
        final boolean accepted;
        final double frameY;
        final double correctionY;
        Measurement(boolean accepted, double frameY, double correctionY) {
            this.accepted = accepted;
            this.frameY = frameY;
            this.correctionY = correctionY;
        }
    }

    private static final class Event {
        final long start;
        final long time;
        final int dy;
        Event(long start, long time, int dy) { this.start = start; this.time = time; this.dy = dy; }
    }
}
