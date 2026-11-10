package com.subhub.app.service;

/** One-shot, non-blocking admission pause for explicitly armed emulator QA. */
final class CaptureGapProbe {
    static final long GAP_MS = 5000;
    enum Action { PASS, START, WAIT, END }
    private boolean consumed;
    private boolean active;
    private long deadline;

    boolean awaitingArm() { return !consumed; }
    long deadline() { return deadline; }

    Action poll(long now, boolean arm) {
        if (now < 0 || now > Long.MAX_VALUE - GAP_MS) return Action.PASS;
        if (active) {
            if (now < deadline) return Action.WAIT;
            active = false;
            return Action.END;
        }
        if (!consumed && arm) {
            consumed = active = true;
            deadline = now + GAP_MS;
            return Action.START;
        }
        return Action.PASS;
    }
}
