package com.subhub.app.appmode;

/** Display the stricter active budget without changing the recorded usage or enforcement state. */
final class LimitsUsage {
    final boolean limited;
    final long remainingMillis;
    final int progressPercent;

    private LimitsUsage(boolean limited, long remainingMillis, int progressPercent) {
        this.limited = limited;
        this.remainingMillis = remainingMillis;
        this.progressPercent = progressPercent;
    }

    static LimitsUsage forApp(
            long appUsed,
            long appBudget,
            long totalUsed,
            long totalBudget,
            boolean individual,
            boolean combined) {
        long remaining = Long.MAX_VALUE;
        int progress = 0;
        if (individual) {
            remaining = remaining(appUsed, appBudget);
            progress = percent(appUsed, appBudget);
        }
        if (combined) {
            remaining = Math.min(remaining, remaining(totalUsed, totalBudget));
            progress = Math.max(progress, percent(totalUsed, totalBudget));
        }
        return new LimitsUsage(individual || combined, remaining, progress);
    }

    private static long remaining(long used, long budget) {
        return used >= budget ? 0L : Math.max(0L, budget - Math.max(0L, used));
    }

    static int percent(long used, long budget) {
        return used <= 0L ? 0 : used >= budget ? 100 : (int) (used * 100L / Math.max(1L, budget));
    }
}
