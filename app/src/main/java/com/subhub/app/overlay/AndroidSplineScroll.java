/*
 * Spline table construction derived from Android Open Source Project OverScroller.
 * Copyright (C) 2010 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * Modified for pure-Java, bounded event-interval reconstruction in SubHub.
 */
package com.subhub.app.overlay;

/** AOSP OverScroller's fixed spline, not a learned app profile. No Android dependency.
 * Source: platform/frameworks/base/core/java/android/widget/OverScroller.java,
 * blob abb147108cac6eb42840772fedf1e2ccf7c8db59.
 */
final class AndroidSplineScroll {
    private static final double RATE = Math.log(.78) / Math.log(.9);
    private static final float[] POSITION = new float[101];
    static {
        float lower = 0;
        for (int i = 0; i < 100; i++) {
            float alpha = i / 100f, upper = 1, x, coefficient, time;
            do {
                x = (lower + upper) / 2;
                coefficient = 3 * x * (1 - x);
                time = coefficient * ((1 - x) * .175f + x * .35f) + x * x * x;
                if (time > alpha) upper = x; else lower = x;
            } while (Math.abs(time - alpha) >= .00001f);
            POSITION[i] = coefficient * ((1 - x) * .5f + x) + x * x * x;
        }
        POSITION[100] = 1;
    }

    private final double coefficient;
    private float duration, elapsed, distance;

    AndroidSplineScroll(float density, float friction) {
        coefficient = 9.80665 * 39.37 * density * 160 * .84 * friction;
    }

    static float fraction(float time) {
        if (time <= 0) return 0;
        if (time >= 1) return 1;
        float index = time * 100;
        int low = (int) index;
        return POSITION[low] + (POSITION[low + 1] - POSITION[low]) * (index - low);
    }

    /** Only identify an ordinary decelerating fling from two known, contiguous intervals.
     * Drag, acceleration, interrupted/edge motion and poor fits stay on the linear fallback.
     * This bounded inversion has no persistent state, training, or per-app tuning data.
     */
    boolean identify(float previousDelta, float previousInterval, float delta, float interval) {
        float previous = Math.abs(previousDelta), current = Math.abs(delta);
        if (!(coefficient > 0) || previousInterval < 8 || interval < 8
                || previousInterval > 250 || interval > 250 || current < 4
                || Math.signum(previousDelta) != Math.signum(delta)
                || current / interval >= previous / previousInterval * .95f) return false;
        float bestError = Float.MAX_VALUE, bestPhase = 0, bestDuration = 0;
        float left = .02f, right = 1;
        // Fixed work bound: 64 coarse candidates, then two 16-candidate refinements.
        for (int pass = 0; pass < 3; pass++) {
            int steps = pass == 0 ? 64 : 16;
            float step = (right - left) / steps;
            for (int i = 0; i <= steps; i++) {
                float phase = left + i * step;
                float lo = (previousInterval + interval) / phase, hi = 8000;
                if (lo > hi || segment(hi, phase, interval) < current
                        || segment(lo, phase, interval) > current) continue;
                for (int iteration = 0; iteration < 24; iteration++) {
                    float mid = (lo + hi) / 2;
                    if (segment(mid, phase, interval) < current) lo = mid; else hi = mid;
                }
                float candidate = (lo + hi) / 2;
                float before = segment(candidate, phase - interval / candidate, previousInterval);
                float error = Math.abs(before - previous);
                if (error < bestError) {
                    bestError = error; bestPhase = phase; bestDuration = candidate;
                }
            }
            left = Math.max(.001f, bestPhase - step);
            right = Math.min(1, bestPhase + step);
        }
        if (bestError > Math.max(2, previous * .03f) || bestDuration <= 0) return false;
        duration = bestDuration;
        elapsed = bestPhase * duration;
        distance = Math.copySign(totalDistance(duration), delta);
        return true;
    }

    private float segment(float total, float phase, float interval) {
        return totalDistance(total) * (fraction(phase) - fraction(phase - interval / total));
    }
    private float totalDistance(float total) {
        return (float) (coefficient * Math.pow(total / 1000, RATE));
    }
    float ahead(float millis) {
        return distance * (fraction((elapsed + Math.max(0, millis)) / duration)
                - fraction(elapsed / duration));
    }
    float velocity(float millis) {
        return ahead(millis + .5f) - ahead(Math.max(0, millis - .5f));
    }
}
