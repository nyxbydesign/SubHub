package com.subhub.app.tasks;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Optional retired-notice failures cannot reject, delay or replace the current task admission. */
final class InternalTaskWorkOrder {
    private InternalTaskWorkOrder() { }
    static <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> work,
            Supplier<? extends CompletableFuture<?>> cleanup, Runnable retryCleanup) {
        CompletableFuture<T> accepted = work.get();
        try { cleanup.get().whenComplete((ignored, failure) -> { if (failure != null) retryCleanup.run(); }); }
        catch (RuntimeException unavailable) { retryCleanup.run(); }
        return accepted;
    }
}
