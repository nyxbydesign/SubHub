package com.subhub.app.appmode;

/** An approved app-specific credit never increases another app's shared allowance. */
public final class ExtraTimeAllowance {
    public static final ExtraTimeAllowance NONE = new ExtraTimeAllowance(0, 0);
    public final long bonusMillis, appCeilingMillis;
    public ExtraTimeAllowance(long bonusMillis, long appCeilingMillis) {
        this.bonusMillis = Math.max(0, bonusMillis);
        this.appCeilingMillis = Math.max(0, appCeilingMillis);
    }
    public long individualBudget(long configuredMillis) {
        return Math.max(Math.min(86_400_000L, configuredMillis + bonusMillis), appCeilingMillis);
    }
    public boolean permitsSharedException(long appUsedMillis) {
        return appCeilingMillis > 0 && Math.max(0, appUsedMillis) < appCeilingMillis;
    }
    public long remaining(long appUsed, long appBudget, long totalUsed, long totalBudget,
            boolean individual, boolean combined) {
        if (!individual && !combined) return 0;
        long result = individual ? Math.max(0, individualBudget(appBudget) - Math.max(0,appUsed)) : Long.MAX_VALUE;
        if (combined) result = Math.min(result, Math.max(Math.max(0,totalBudget - Math.max(0,totalUsed)),
                Math.max(0,appCeilingMillis - Math.max(0,appUsed))));
        return result;
    }
}
