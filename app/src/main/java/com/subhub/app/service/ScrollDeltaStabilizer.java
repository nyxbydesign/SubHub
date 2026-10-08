package com.subhub.app.service;

/**
 * Rejects isolated direction reversals without discarding valid Accessibility scroll distance.
 *
 * <p>Some RecyclerView producers emit correction records whose sign is opposite to the visible
 * gesture. Applying each low-confidence record literally makes an otherwise fast overlay bounce.
 * Explicit and absolute Accessibility deltas bypass this heuristic because their timing is
 * authoritative. Fallback indexed motion accepts a genuine reversal after two consecutive
 * meaningful samples, but never folds withheld displacement into a later oversized event.
 * Strictly alternating input enters pass-through after four samples. A gesture gap clears
 * direction so a deliberate new gesture is accepted immediately.</p>
 */
final class ScrollDeltaStabilizer {
    private static final long SESSION_GAP_MS = 250L;
    private static final int MEANINGFUL_DELTA_PX = 8;

    private final Axis x = new Axis();
    private final Axis y = new Axis();
    private long lastSampleUptime;

    synchronized Result filter(
            int rawDx,
            int rawDy,
            long nowUptime,
            int viewportWidth,
            int viewportHeight) {
        return filter(rawDx, rawDy, nowUptime, viewportWidth, viewportHeight, false);
    }

    synchronized Result filter(
            int rawDx,
            int rawDy,
            long nowUptime,
            int viewportWidth,
            int viewportHeight,
            boolean authoritative) {
        if (lastSampleUptime <= 0L || nowUptime - lastSampleUptime > SESSION_GAP_MS) {
            x.startSession();
            y.startSession();
        }
        lastSampleUptime = nowUptime;
        // The resolver already bounds producer deltas to two viewports. Applying a second,
        // half-viewport clamp here permanently discarded most of each fast Chrome scroll event
        // (for example 5,984px reported versus 1,496px applied on the Pixel 8 Pro). That made
        // every track trail the page even after Android delivered the authoritative displacement.
        int dx = authoritative
                ? x.acceptAuthoritative(rawDx, displacementLimit(viewportWidth))
                : x.filter(rawDx, displacementLimit(viewportWidth));
        int dy = authoritative
                ? y.acceptAuthoritative(rawDy, displacementLimit(viewportHeight))
                : y.filter(rawDy, displacementLimit(viewportHeight));
        return new Result(rawDx, rawDy, dx, dy,
                x.wasRapidReversalOutput() || y.wasRapidReversalOutput(), authoritative);
    }

    synchronized void reset() {
        x.reset();
        y.reset();
        lastSampleUptime = 0L;
    }

    private static final class Axis {
        private int direction;
        private int oppositeCount;
        private int lastRawDirection;
        private int alternatingTransitions;
        private boolean rapidReversalMode;
        private int rapidSameDirectionSamples;
        private boolean rapidReversalOutput;

        int filter(int raw, int limit) {
            rapidReversalOutput = false;
            int clamped = clamp(raw, -limit, limit);
            int magnitude = Math.abs(clamped);
            if (magnitude < MEANINGFUL_DELTA_PX) {
                return direction != 0 && (clamped == 0
                        || Integer.signum(clamped) == direction) ? clamped : 0;
            }
            int sign = Integer.signum(clamped);
            boolean rawReversed = lastRawDirection != 0 && sign != lastRawDirection;
            if (rawReversed) {
                alternatingTransitions++;
                rapidSameDirectionSamples = 1;
            } else {
                alternatingTransitions = 0;
                rapidSameDirectionSamples++;
            }
            lastRawDirection = sign;

            if (rapidReversalMode) {
                rapidReversalOutput = true;
                direction = sign;
                oppositeCount = 0;
                if (!rawReversed && rapidSameDirectionSamples >= 3) {
                    rapidReversalMode = false;
                    alternatingTransitions = 0;
                }
                return clamped;
            }

            if (alternatingTransitions >= 3) {
                // The previous samples proved the alternating input is deliberate. Enter
                // pass-through without lumping a withheld sample into this event: Accessibility
                // timing is authoritative, and debt reconciliation creates a visible double jump.
                rapidReversalMode = true;
                rapidReversalOutput = true;
                direction = sign;
                oppositeCount = 0;
                return clamped;
            }

            if (direction == 0) {
                direction = sign;
                oppositeCount = 0;
                return clamped;
            }
            if (direction != 0 && sign == direction) {
                oppositeCount = 0;
                return clamped;
            }
            oppositeCount++;
            if (oppositeCount < 2) {
                return 0;
            }

            direction = sign;
            oppositeCount = 0;
            return clamped;
        }

        boolean wasRapidReversalOutput() {
            return rapidReversalOutput;
        }

        int acceptAuthoritative(int raw, int limit) {
            int clamped = clamp(raw, -limit, limit);
            rapidReversalOutput = false;
            if (Math.abs(clamped) >= MEANINGFUL_DELTA_PX) {
                direction = Integer.signum(clamped);
                lastRawDirection = direction;
            }
            oppositeCount = 0;
            alternatingTransitions = 0;
            rapidReversalMode = false;
            rapidSameDirectionSamples = clamped == 0 ? 0 : 1;
            return clamped;
        }

        void startSession() {
            direction = 0;
            oppositeCount = 0;
            lastRawDirection = 0;
            alternatingTransitions = 0;
            rapidReversalMode = false;
            rapidSameDirectionSamples = 0;
            rapidReversalOutput = false;
        }

        void reset() {
            startSession();
        }
    }

    static final class Result {
        final int rawDx;
        final int rawDy;
        final int dx;
        final int dy;
        final boolean rapidReversal;
        final boolean authoritative;

        Result(
                int rawDx,
                int rawDy,
                int dx,
                int dy,
                boolean rapidReversal,
                boolean authoritative) {
            this.rawDx = rawDx;
            this.rawDy = rawDy;
            this.dx = dx;
            this.dy = dy;
            this.rapidReversal = rapidReversal;
            this.authoritative = authoritative;
        }

        boolean moved() { return dx != 0 || dy != 0; }
        boolean changed() { return rawDx != dx || rawDy != dy; }
        int adjustedPixels() {
            return Math.abs(rawDx - dx) + Math.abs(rawDy - dy);
        }
        boolean amplified() {
            return Math.abs((long) dx) + Math.abs((long) dy)
                    > Math.abs((long) rawDx) + Math.abs((long) rawDy);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int displacementLimit(int viewportSize) {
        long doubled = Math.max(1L, viewportSize) * 2L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(32L, doubled));
    }
}
