package com.subhub.app.service;

/** Separates an observed foreground identity from a requested departure after a spent budget. */
final class ForegroundLimitHandoff {
    private String observed = "";
    private boolean reached, awaitingHome;

    void observed(String packageName) {
        String next = packageName == null ? "" : packageName;
        if (next.equals(observed)) return;
        observed = next;
        reached = false;
        awaitingHome = false;
    }

    void budgetReached(boolean value) {
        reached = value;
        if (!value) awaitingHome = false;
    }

    void homeRequested() { if (reached) awaitingHome = true; }
    boolean effectsAllowed() { return !reached; }
    boolean accountingAllowed() { return !awaitingHome; }
}
