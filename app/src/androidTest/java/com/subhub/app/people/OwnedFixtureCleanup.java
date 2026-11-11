package com.subhub.app.people;

/** Each independent owned-fixture cleanup runs even when a previous assertion or restore fails. */
final class OwnedFixtureCleanup {
    @FunctionalInterface interface Step { void run() throws Exception; }
    private Throwable failure;
    void attempt(Step step) {
        try { step.run(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
    }
    void finish() throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }
}
