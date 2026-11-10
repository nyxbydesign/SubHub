package com.subhub.app.overlay;

/** Source-clocked, bounded continuation for native X RecyclerView scroll events. */
final class NativeScrollTrajectory {
    private final AndroidSplineScroll spline;
    private float exact, speed, budget, error, errorVelocity, previousDelta, previousInterval;
    private float olderDelta, olderInterval;
    private float heldPosition, direction, expiry, peak;
    private long source = -1, delivered;
    private boolean sampled, useSpline, hold, braking;

    NativeScrollTrajectory(float density, float friction) {
        spline = new AndroidSplineScroll(density, friction);
    }
    void reset(float value, long now) {
        exact = value; source = -1; delivered = now;
        speed = budget = error = errorVelocity = previousDelta = previousInterval = peak = 0;
        olderDelta = olderInterval = 0;
        sampled = useSpline = hold = braking = false;
    }
    void measure(float measured, float delta, long sourceTime, long now, int viewport) {
        if (sampled && sourceTime <= source) {
            // Preserve camera-authoritative distance, but an old record cannot seed a
            // fresh physical interval. Correct without extrapolation and reacquire.
            float before = position(now);
            exact = measured; delivered = now; error = before - exact;
            errorVelocity = speed = peak = previousInterval = olderInterval = 0;
            useSpline = hold = false; braking = true;
            return;
        }
        float before = position(now), beforeVelocity = velocity(now);
        long interval = source < 0 ? 0 : sourceTime - source;
        boolean contiguous = sampled && interval >= 8 && interval <= 250;
        boolean sameDirection = contiguous && delta != 0
                && Math.signum(delta) == Math.signum(previousDelta);
        boolean discontinuity = !sampled || !contiguous
                || Math.abs(measured - before) > Math.max(96, viewport * .5f);
        // Two intervals identify a candidate; a third independently validates its shape.
        // Accessibility does not expose touch-vs-fling state, so never call a lone delta
        // or arbitrary slowdown an observed fling. Reject interrupted/poor-fit motion.
        useSpline = sameDirection && olderInterval >= 8
                && spline.identify(olderDelta, olderInterval, previousDelta, previousInterval)
                && Math.abs(spline.ahead(interval) - delta) <= Math.max(3, Math.abs(delta) * .05f)
                && spline.identify(previousDelta, previousInterval, delta, interval);
        speed = contiguous ? delta / interval : 0;
        if (sameDirection && previousInterval >= 8
                && Math.abs(speed) < Math.abs(previousDelta / previousInterval)) {
            // End-of-interval velocity, not the average velocity of the historical interval.
            speed = Math.copySign(Math.max(0, 2 * Math.abs(speed)
                    - Math.abs(previousDelta / previousInterval)), speed);
        } else if (contiguous && !sameDirection) speed = 0;
        exact = measured; source = sourceTime; delivered = now;
        float deliveryAge = Math.max(0, now - sourceTime);
        // Account for this callback's observed transport age, not a saved lag profile.
        // An already-expired event never gains another interval simply by arriving late.
        boolean expired = contiguous && deliveryAge > interval + 24;
        if (expired) { speed = 0; useSpline = false; }
        expiry = contiguous && !expired ? interval + Math.max(24, deliveryAge) : 0;
        budget = Math.min(Math.abs(delta) * 1.25f + Math.abs(speed) * deliveryAge,
                Math.max(24, viewport * .45f));
        peak = rawAhead(expiry);
        if (discontinuity) { before = measured; beforeVelocity = 0; }
        error = before - exact - forecast(Math.max(0, now - source));
        errorVelocity = beforeVelocity - forecastVelocity(Math.max(0, now - source));
        heldPosition = before; direction = Math.signum(delta);
        hold = sameDirection && direction * (before - exact) > 0;
        braking = contiguous && (!sameDirection || delta == 0);
        if (braking) {
            useSpline = false; speed = peak = 0; error = before - exact;
            errorVelocity = 0;
        }
        olderDelta = previousDelta;
        olderInterval = previousInterval;
        previousDelta = delta;
        previousInterval = contiguous ? interval : 0;
        sampled = true;
    }
    float position(long now) {
        if (!sampled) return exact;
        float age = Math.max(0, now - source), correctionAge = Math.max(0, now - delivered);
        if (braking) return exact + error * (1 - smooth(Math.min(1, correctionAge / 32)));
        float projected = exact + forecast(age);
        if (hold && age <= expiry) return direction > 0
                ? Math.max(heldPosition, projected + correction(correctionAge))
                : Math.min(heldPosition, projected + correction(correctionAge));
        if (hold) {
            float end = peak + correction(Math.max(0, source + expiry - delivered));
            float lead = direction > 0 ? Math.max(heldPosition - exact, end)
                    : Math.min(heldPosition - exact, end);
            return exact + lead * (1 - smooth(Math.min(1, (age - expiry) / 96)));
        }
        return projected + correction(correctionAge);
    }
    float velocity(long now) {
        return (position(now + 1) - position(now)) ;
    }
    private float correction(float age) {
        if (age >= 160) return 0;
        return (float) ((error + (errorVelocity + .14f * error) * age) * Math.exp(-.14f * age));
    }
    private float rawAhead(float age) {
        float ahead = useSpline ? spline.ahead(age) : speed * age;
        return Math.max(-budget, Math.min(budget, ahead));
    }
    private float forecast(float age) {
        if (age <= expiry) return rawAhead(age);
        return peak * (1 - smooth(Math.min(1, (age - expiry) / 96)));
    }
    private float forecastVelocity(float age) { return forecast(age + 1) - forecast(age); }
    boolean isAnimating(long now) {
        if (!sampled) return false;
        if (braking) return Math.abs(error) > .001f && now - delivered < 32;
        return (now - source < expiry + 96 || now - delivered < 160)
                && (Math.abs(peak) > .001f || Math.abs(error) > .001f || Math.abs(errorVelocity) > .001f);
    }
    float predictionAmplitude() { return peak; }
    long predictionPeakMillis() { return Math.round(expiry); }
    boolean usesSpline() { return useSpline; }
    private static float smooth(float t) { return t * t * t * (t * (t * 6 - 15) + 10); }
}
