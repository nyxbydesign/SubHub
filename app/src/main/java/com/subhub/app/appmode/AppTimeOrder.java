package com.subhub.app.appmode;

/** Orders displayed allowances by remaining fraction, not absolute minutes or rounded percentages. */
public final class AppTimeOrder {
    private AppTimeOrder() { }
    public static double remainingFraction(long usedMillis, long budgetMillis) {
        if (usedMillis < 0 || budgetMillis <= 0) return Double.POSITIVE_INFINITY;
        return usedMillis >= budgetMillis ? 0d : (budgetMillis - usedMillis) / (double) budgetMillis;
    }
    public static int compare(long leftUsed, long leftBudget, String leftLabel, String leftPackage,
            long rightUsed, long rightBudget, String rightLabel, String rightPackage) {
        return compareFractions(remainingFraction(leftUsed, leftBudget), leftLabel, leftPackage,
                remainingFraction(rightUsed, rightBudget), rightLabel, rightPackage);
    }
    public static int compareFractions(double left, String leftLabel, String leftPackage,
            double right, String rightLabel, String rightPackage) {
        int order = Double.compare(left, right);
        if (order == 0) order = text(leftLabel).compareToIgnoreCase(text(rightLabel));
        if (order == 0) order = text(leftPackage).compareTo(text(rightPackage));
        return order;
    }
    /** Each app ranks by its individual allowance when enabled; the shared allowance is separate.
     * Shared-only screens use the shared budget plus any applied app-specific exception. */
    public static double forApp(long appUsed, long appBudget, long totalUsed, long totalBudget,
            boolean individual, boolean combined, ExtraTimeAllowance extra) {
        if (individual) return remainingFraction(appUsed, extra.individualBudget(appBudget));
        if (!combined || appUsed < 0 || totalUsed < 0 || totalBudget <= 0) return Double.POSITIVE_INFINITY;
        long budget = Math.max(totalBudget, extra.appCeilingMillis);
        long available = Math.max(Math.max(0L,totalBudget-totalUsed),Math.max(0L,extra.appCeilingMillis-appUsed));
        return Math.min(1d,available/(double)budget);
    }
    /** A newer peer can report its actually applied credit. Decisions alone do not establish it. */
    public static long confirmedBudget(long configured, long reportedConfigured, long reportedEffective) {
        return reportedConfigured == configured && reportedEffective >= configured && reportedEffective <= 86_400_000L
                ? reportedEffective : configured;
    }
    private static String text(String value) { return value == null ? "" : value; }
}
