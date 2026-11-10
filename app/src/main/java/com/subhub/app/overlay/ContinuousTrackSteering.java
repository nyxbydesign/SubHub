package com.subhub.app.overlay;

import com.subhub.app.detection.BBox;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Persistent display-time geometry steering keyed by stable tracker identity.
 *
 * <p>Detector publications update measured targets; they never replace the currently displayed
 * geometry. Viewport prediction is deliberately handled only by {@link ViewportMotion}. A second
 * predictor here used to push boxes beyond detector geometry and then pull them back, creating the
 * conspicuous late "prettier pass" even after the page had stopped.</p>
 */
final class ContinuousTrackSteering {
    private static final float POSITION_RESPONSE_TIME_MS = 30f;
    private static final float SIZE_RESPONSE_TIME_MS = 36f;
    private static final float POSITION_EPSILON = 0.0004f;
    private static final float SIZE_EPSILON = 0.0003f;

    private final Map<Integer, State> states = new HashMap<>();

    void updateTarget(
            int id,
            BBox box,
            int sourceWidth,
            int sourceHeight,
            long nowMillis,
            boolean unusedPredictMotion) {
        if (box == null) return;
        float width = Math.max(1, sourceWidth);
        float height = Math.max(1, sourceHeight);
        float targetWidth = box.getWidth() / width;
        float targetHeight = box.getHeight() / height;
        float targetCenterX = (box.getX() + box.getWidth() * 0.5f) / width;
        float targetCenterY = (box.getY() + box.getHeight() * 0.5f) / height;
        State state = states.get(id);
        if (state == null) {
            states.put(id, new State(targetCenterX, targetCenterY,
                    targetWidth, targetHeight, nowMillis));
            return;
        }
        state.updateTarget(targetCenterX, targetCenterY, targetWidth, targetHeight, nowMillis);
    }

    BBox position(int id, int sourceWidth, int sourceHeight, long nowMillis) {
        State state = states.get(id);
        if (state == null) return null;
        state.advance(nowMillis);
        int width = Math.max(1, sourceWidth);
        int height = Math.max(1, sourceHeight);
        int boxWidth = Math.max(1, Math.round(state.currentWidth * width));
        int boxHeight = Math.max(1, Math.round(state.currentHeight * height));
        int x = Math.round(state.currentCenterX * width - boxWidth * 0.5f);
        int y = Math.round(state.currentCenterY * height - boxHeight * 0.5f);
        return new BBox(x, y, boxWidth, boxHeight);
    }

    void offsetAll(float normalizedDx, float normalizedDy, long nowMillis) {
        if (normalizedDx == 0f && normalizedDy == 0f) return;
        for (State state : states.values()) {
            state.advance(nowMillis);
            state.currentCenterX += normalizedDx;
            state.currentCenterY += normalizedDy;
            state.targetCenterX += normalizedDx;
            state.targetCenterY += normalizedDy;
        }
    }

    void retain(Set<Integer> activeIds) {
        states.keySet().retainAll(activeIds);
    }

    void clear() {
        states.clear();
    }

    void forget(int id) { states.remove(id); }

    boolean isAnimating(long nowMillis) {
        for (State state : states.values()) {
            state.advance(nowMillis);
            if (state.isAnimating()) return true;
        }
        return false;
    }

    int size() {
        return states.size();
    }

    private static final class State {
        private float currentCenterX;
        private float currentCenterY;
        private float currentWidth;
        private float currentHeight;
        private float targetCenterX;
        private float targetCenterY;
        private float targetWidth;
        private float targetHeight;
        private long lastSampleMillis;

        State(
                float centerX,
                float centerY,
                float width,
                float height,
                long nowMillis) {
            currentCenterX = targetCenterX = centerX;
            currentCenterY = targetCenterY = centerY;
            currentWidth = targetWidth = width;
            currentHeight = targetHeight = height;
            lastSampleMillis = nowMillis;
        }

        void updateTarget(
                float centerX,
                float centerY,
                float width,
                float height,
                long nowMillis) {
            advance(nowMillis);
            targetCenterX = centerX;
            targetCenterY = centerY;
            targetWidth = Math.max(SIZE_EPSILON, width);
            targetHeight = Math.max(SIZE_EPSILON, height);
        }

        void advance(long nowMillis) {
            long elapsed = Math.max(0L, nowMillis - lastSampleMillis);
            if (elapsed <= 0L) return;
            float positionAlpha = 1f - (float) Math.exp(-elapsed / POSITION_RESPONSE_TIME_MS);
            float sizeAlpha = 1f - (float) Math.exp(-elapsed / SIZE_RESPONSE_TIME_MS);
            currentCenterX += (targetCenterX - currentCenterX) * positionAlpha;
            currentCenterY += (targetCenterY - currentCenterY) * positionAlpha;
            currentWidth += (targetWidth - currentWidth) * sizeAlpha;
            currentHeight += (targetHeight - currentHeight) * sizeAlpha;
            if (Math.abs(targetCenterX - currentCenterX) <= POSITION_EPSILON) {
                currentCenterX = targetCenterX;
            }
            if (Math.abs(targetCenterY - currentCenterY) <= POSITION_EPSILON) {
                currentCenterY = targetCenterY;
            }
            if (Math.abs(targetWidth - currentWidth) <= SIZE_EPSILON) {
                currentWidth = targetWidth;
            }
            if (Math.abs(targetHeight - currentHeight) <= SIZE_EPSILON) {
                currentHeight = targetHeight;
            }
            lastSampleMillis = nowMillis;
        }

        boolean isAnimating() {
            return Math.abs(targetCenterX - currentCenterX) > POSITION_EPSILON
                    || Math.abs(targetCenterY - currentCenterY) > POSITION_EPSILON
                    || Math.abs(targetWidth - currentWidth) > SIZE_EPSILON
                    || Math.abs(targetHeight - currentHeight) > SIZE_EPSILON;
        }
    }
}
