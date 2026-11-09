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
        int order = Double.compare(remainingFraction(leftUsed, leftBudget), remainingFraction(rightUsed, rightBudget));
        if (order == 0) order = text(leftLabel).compareToIgnoreCase(text(rightLabel));
        if (order == 0) order = text(leftPackage).compareTo(text(rightPackage));
        return order;
    }
    /** A newer peer can report its actually applied credit. Decisions alone do not establish it. */
    public static long confirmedBudget(long configured, long reportedConfigured, long reportedEffective) {
        return reportedConfigured == configured && reportedEffective >= configured && reportedEffective <= 86_400_000L
                ? reportedEffective : configured;
    }
    private static String text(String value) { return value == null ? "" : value; }
}
