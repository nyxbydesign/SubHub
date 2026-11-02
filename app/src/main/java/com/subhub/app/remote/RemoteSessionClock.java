package com.subhub.app.remote;

/** Receiver-local clocks bound a command's lifetime independently of wall-clock corrections. */
public final class RemoteSessionClock {
    private static volatile int observedBoot = -1;
    private RemoteSessionClock() { }
    static Sample observe(android.content.Context context) {
        int boot = android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        if (boot >= 0) observedBoot = boot;
        return new Sample(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), boot);
    }
    static Sample local() { return new Sample(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), observedBoot); }
    public static final class Sample {
        public final long wall, elapsed;
        public final int boot;
        public Sample(long wall, long elapsed, int boot) {
            this.wall = wall; this.elapsed = elapsed; this.boot = boot;
        }
    }
    public static final class Bounds {
        public final long receivedWall, receivedElapsed, deadlineElapsed;
        public final int boot;
        public Bounds(long receivedWall, long receivedElapsed, int boot, long deadlineElapsed) {
            this.receivedWall = receivedWall; this.receivedElapsed = receivedElapsed;
            this.boot = boot; this.deadlineElapsed = deadlineElapsed;
        }
        static Bounds begin(RemoteSessionCommands.Request request, Sample now) {
            if (now.wall <= 0 || now.elapsed < 0 || now.boot < 0
                    || now.elapsed > Long.MAX_VALUE - RemoteSessionCommands.LIFETIME_MILLIS)
                return new Bounds(now.wall, now.elapsed, -1, 0);
            long remaining = request.expiresAt <= now.wall ? 0
                    : Math.min(RemoteSessionCommands.LIFETIME_MILLIS, request.expiresAt - now.wall);
            return new Bounds(now.wall, now.elapsed, now.boot, now.elapsed + remaining);
        }
        public RemoteSessionCommands.Reason check(RemoteSessionCommands.Request request, Sample now) {
            if (receivedWall <= 0 || receivedElapsed < 0 || boot < 0 || now.boot != boot
                    || now.wall <= 0 || now.elapsed < receivedElapsed || now.wall < receivedWall)
                return RemoteSessionCommands.Reason.CLOCK_CHANGED;
            long elapsed = now.elapsed - receivedElapsed;
            long wall = now.wall - receivedWall;
            // Compare against the original anchor, so small repeated corrections cannot renew a budget.
            if (Math.abs(wall - elapsed) > 2000) return RemoteSessionCommands.Reason.CLOCK_CHANGED;
            if (now.elapsed >= deadlineElapsed || now.wall >= request.expiresAt)
                return RemoteSessionCommands.Reason.EXPIRED;
            if (request.issuedAt - now.wall > RemoteSessionCommands.CLOCK_SKEW_MILLIS)
                return RemoteSessionCommands.Reason.CLOCK_CHANGED;
            return RemoteSessionCommands.Reason.NONE;
        }
        boolean retentionElapsed(Sample now) {
            if (now.boot == boot && receivedElapsed >= 0 && now.elapsed >= receivedElapsed)
                return now.elapsed - receivedElapsed >= RemoteSessionCommands.LIFETIME_MILLIS;
            return now.wall >= receivedWall && receivedWall > 0
                    && now.wall - receivedWall >= RemoteSessionCommands.LIFETIME_MILLIS;
        }
    }
}
