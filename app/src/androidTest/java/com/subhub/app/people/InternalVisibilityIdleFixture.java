package com.subhub.app.people;

import android.content.Context;
import com.subhub.app.internal.InternalSubRecord;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** An explicitly idle synthetic owner, scoped to one fixture context/backend, never a production inference. */
final class InternalVisibilityIdleFixture implements AutoCloseable {
    private final Field provider;
    private final Object original;
    InternalVisibilityIdleFixture(Context context, Predicate<InternalSubData> owned) throws Exception {
        provider = InternalVisibilityBarrier.class.getDeclaredField("provider"); provider.setAccessible(true);
        original = provider.get(null);
        provider.set(null, (InternalVisibilityBarrier.Provider) (physical, request) -> {
            InternalSubData data = InternalSubData.peek(physical);
            if (physical != context || data == null || !owned.test(data) || !request.expected.equals(data.acknowledged()))
                throw new AssertionError("Idle proof requested outside owned fixture");
            AtomicBoolean completed = new AtomicBoolean();
            return CompletableFuture.completedFuture(new InternalVisibilityBarrier.Permit() {
                @Override public void requireCurrent() {
                    if (completed.get() || InternalSubData.peek(physical) != data || !request.expected.equals(data.acknowledged()))
                        throw new SecurityException("Owned idle proof changed");
                }
                @Override public CompletableFuture<Void> complete(InternalVisibilityBarrier.Completion completion) {
                    if (!completed.compareAndSet(false, true)) throw new AssertionError("Idle proof completed twice");
                    InternalSubRecord acknowledged = completion.acknowledged;
                    if (completion.outcome == InternalVisibilityBarrier.Outcome.ACKNOWLEDGED && (acknowledged == null || acknowledged.visible != request.visible
                            || acknowledged.generation.equals(request.expected.generation)
                            || acknowledged.revision != request.expected.revision + 1))
                        throw new AssertionError("Owned visibility acknowledgement missing");
                    return CompletableFuture.completedFuture(null);
                }
            });
        });
    }
    @Override public void close() throws Exception { provider.set(null, original); }
    void assertOriginalRestored() throws Exception {
        if(provider.get(null)!=original)throw new AssertionError("Exact original visibility provider not restored");
    }
}
