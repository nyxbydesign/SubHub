package com.subhub.app.remote;

import android.content.Context;

/** Conservative local-review clocks; uncertain boot/wall observations never release a reservation. */
final class FormalRelationshipClock {
    static final long REVIEW_MILLIS = 120_000, RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;
    static final class Sample {
        final long wall, elapsed; final int boot;
        Sample(long wall, long elapsed, int boot) { this.wall = wall; this.elapsed = elapsed; this.boot = boot; }
        void requireValid() {
            if (wall <= 0 || elapsed < 0 || boot < 0) throw new IllegalStateException("Clock unavailable");
        }
    }
    private FormalRelationshipClock() { }
    interface Source {
        Sample observe();
        Sample transition(Sample observed);
    }
    static Source source(Context context) {
        return new Source() {
            @Override public Sample observe() { return FormalRelationshipClock.observe(context); }
            @Override public Sample transition(Sample observed) {
                // BOOT_COUNT was queried outside locks; only cheap local time is sampled at admission.
                return new Sample(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), observed.boot);
            }
        };
    }
    static Sample observe(Context context) {
        RemoteSessionClock.Sample observed = RemoteSessionClock.observe(context);
        return new Sample(observed.wall, observed.elapsed, observed.boot);
    }
    static boolean consistent(Sample original, Sample current) {
        if (original.wall <= 0 || original.elapsed < 0 || original.boot < 0 || current.boot != original.boot
                || current.wall < original.wall || current.elapsed < original.elapsed) return false;
        long elapsed = current.elapsed - original.elapsed, wall = current.wall - original.wall;
        return wall >= elapsed ? wall - elapsed <= 2000 : elapsed - wall <= 2000;
    }
    static boolean live(Sample began, Sample current) {
        return consistent(began, current) && current.elapsed - began.elapsed < REVIEW_MILLIS;
    }
    static boolean expired(Sample began, Sample current) {
        return consistent(began, current) && current.elapsed - began.elapsed >= REVIEW_MILLIS;
    }
    static boolean oldTerminal(Sample terminal, Sample current) {
        return consistent(terminal, current) && current.elapsed - terminal.elapsed >= RETENTION_MILLIS;
    }
}
