package com.subhub.app.service;

import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityRecord;

import androidx.annotation.RequiresApi;

/** Converts Accessibility scroll metadata into immediate screen-pixel overlay motion. */
final class AccessibilityScrollMotionResolver {
    private String absoluteKey = "";
    private int absoluteX = -1;
    private int absoluteY = -1;
    private String indexKey = "";
    private int firstVisibleIndex = -1;

    synchronized Motion resolve(AccessibilityEvent event, int viewportWidth, int viewportHeight) {
        return resolve(event, viewportWidth, viewportHeight, surfaceKey(event));
    }

    synchronized Motion resolve(
            AccessibilityEvent event,
            int viewportWidth,
            int viewportHeight,
            String resolvedSurfaceKey) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return Motion.NONE;
        }
        String key = resolvedSurfaceKey == null || resolvedSurfaceKey.isEmpty()
                ? surfaceKey(event) : resolvedSurfaceKey;
        int safeWidth = Math.max(1, viewportWidth);
        int safeHeight = Math.max(1, viewportHeight);
        Motion explicit = explicitMotion(event, safeWidth, safeHeight);
        if (explicit.moved()) {
            rememberAbsolute(event, key);
            rememberIndex(event, key);
            return explicit;
        }

        int currentX = event.getScrollX();
        int currentY = event.getScrollY();
        if (!key.equals(absoluteKey)) {
            absoluteKey = key;
            absoluteX = currentX;
            absoluteY = currentY;
        } else if (currentX >= 0 || currentY >= 0) {
            int contentDx = currentX >= 0 && absoluteX >= 0 ? currentX - absoluteX : 0;
            int contentDy = currentY >= 0 && absoluteY >= 0 ? currentY - absoluteY : 0;
            absoluteX = currentX;
            absoluteY = currentY;
            if (Math.abs(contentDx) <= safeWidth * 2
                    && Math.abs(contentDy) <= safeHeight * 2) {
                Motion absolute = screenMotion(
                        contentDx, contentDy, safeWidth, safeHeight,
                        Motion.Evidence.ABSOLUTE);
                if (absolute.moved()) {
                    rememberIndex(event, key);
                    return absolute;
                }
            }
        }
        return indexedMotion(event, key, safeWidth, safeHeight);
    }

    synchronized void reset() {
        absoluteKey = "";
        absoluteX = -1;
        absoluteY = -1;
        indexKey = "";
        firstVisibleIndex = -1;
    }

    private static Motion explicitMotion(
            AccessibilityEvent event, int viewportWidth, int viewportHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Motion.NONE;
        Motion best = motionFromRecord(event, viewportWidth, viewportHeight);
        for (int index = 0; index < event.getRecordCount(); index++) {
            Motion candidate = motionFromRecord(
                    event.getRecord(index), viewportWidth, viewportHeight);
            if (candidate.magnitude() > best.magnitude()) best = candidate;
        }
        return best;
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private static Motion motionFromRecord(
            AccessibilityRecord record, int viewportWidth, int viewportHeight) {
        int contentDx = normalizeDelta(record.getScrollDeltaX());
        int contentDy = normalizeDelta(record.getScrollDeltaY());
        return screenMotion(contentDx, contentDy, viewportWidth, viewportHeight,
                Motion.Evidence.EXPLICIT);
    }

    private static int normalizeDelta(int value) {
        // AccessibilityRecord uses -1 when a producer omitted this property. A one-pixel report
        // is visually irrelevant and indistinguishable from that sentinel.
        return Math.abs(value) <= 1 ? 0 : value;
    }

    private static Motion screenMotion(
            int contentDx,
            int contentDy,
            int viewportWidth,
            int viewportHeight,
            Motion.Evidence evidence) {
        int limitX = Math.max(1, viewportWidth * 2);
        int limitY = Math.max(1, viewportHeight * 2);
        int screenDx = clamp(-contentDx, -limitX, limitX);
        int screenDy = clamp(-contentDy, -limitY, limitY);
        return screenDx == 0 && screenDy == 0
                ? Motion.NONE : new Motion(screenDx, screenDy, evidence);
    }

    private void rememberAbsolute(AccessibilityEvent event, String key) {
        int currentX = event.getScrollX();
        int currentY = event.getScrollY();
        if (currentX < 0 && currentY < 0) return;
        absoluteKey = key;
        absoluteX = currentX;
        absoluteY = currentY;
    }

    private Motion indexedMotion(
            AccessibilityEvent event, String key, int viewportWidth, int viewportHeight) {
        int currentFrom = event.getFromIndex();
        int currentTo = event.getToIndex();
        if (currentFrom < 0 || currentTo < currentFrom) {
            indexKey = key;
            firstVisibleIndex = -1;
            return Motion.NONE;
        }
        if (!key.equals(indexKey) || firstVisibleIndex < 0) {
            indexKey = key;
            firstVisibleIndex = currentFrom;
            return Motion.NONE;
        }
        int itemDelta = currentFrom - firstVisibleIndex;
        firstVisibleIndex = currentFrom;
        if (itemDelta == 0) return Motion.NONE;
        int visibleItems = Math.max(1, currentTo - currentFrom + 1);
        int estimatedItemHeight = Math.max(1, viewportHeight / visibleItems);
        return screenMotion(0, itemDelta * estimatedItemHeight,
                viewportWidth, viewportHeight, Motion.Evidence.INDEXED);
    }

    private void rememberIndex(AccessibilityEvent event, String key) {
        int currentFrom = event.getFromIndex();
        int currentTo = event.getToIndex();
        if (currentFrom < 0 || currentTo < currentFrom) return;
        indexKey = key;
        firstVisibleIndex = currentFrom;
    }

    static String surfaceKey(AccessibilityEvent event) {
        if (event == null) return "";
        String packageName = event.getPackageName() == null
                ? "" : event.getPackageName().toString();
        String className = event.getClassName() == null
                ? "" : event.getClassName().toString();
        return packageName + '|' + event.getWindowId() + '|' + className;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Motion {
        enum Evidence {
            NONE,
            EXPLICIT,
            ABSOLUTE,
            INDEXED
        }

        static final Motion NONE = new Motion(0, 0, Evidence.NONE);
        final int dx;
        final int dy;
        final Evidence evidence;

        Motion(int dx, int dy, Evidence evidence) {
            this.dx = dx;
            this.dy = dy;
            this.evidence = evidence == null ? Evidence.NONE : evidence;
        }

        boolean moved() { return dx != 0 || dy != 0; }
        long magnitude() { return Math.abs((long) dx) + Math.abs((long) dy); }
        boolean authoritative() {
            return evidence == Evidence.EXPLICIT || evidence == Evidence.ABSOLUTE;
        }
    }
}
