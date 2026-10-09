package com.subhub.app.detection.text;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keeps a late SDK completion from throwing into its caller after the OCR worker stops. */
final class OcrCallbackDispatcher {
    private OcrCallbackDispatcher() {}

    static void dispatch(Executor worker, Runnable work,
                         Consumer<RejectedExecutionException> rejectedCleanup) {
        AtomicBoolean started = new AtomicBoolean();
        try {
            worker.execute(() -> {
                started.set(true);
                work.run();
            });
        } catch (RejectedExecutionException rejected) {
            // A direct executor's callback error is not an executor-admission failure.
            if (started.get()) throw rejected;
            rejectedCleanup.accept(rejected);
        }
    }
}
