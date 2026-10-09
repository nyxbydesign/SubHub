package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class OcrCallbackDispatcherTest {
    @Test public void acceptedWorkRunsOnceWithoutCleanup() {
        AtomicInteger work = new AtomicInteger(), rejected = new AtomicInteger();
        OcrCallbackDispatcher.dispatch(Runnable::run, work::incrementAndGet,
                error -> rejected.incrementAndGet());
        assertEquals(1, work.get());
        assertEquals(0, rejected.get());
    }

    @Test public void stoppedWorkerOnlyRunsFailureCleanup() {
        AtomicInteger work = new AtomicInteger(), rejected = new AtomicInteger();
        OcrCallbackDispatcher.dispatch(command -> { throw new RejectedExecutionException(); },
                work::incrementAndGet, error -> rejected.incrementAndGet());
        assertEquals(0, work.get());
        assertEquals(1, rejected.get());
    }

    @Test public void queuedWorkDoesNotRunOnSdkCompletionThread() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger work = new AtomicInteger();
        OcrCallbackDispatcher.dispatch(queued::set, work::incrementAndGet,
                error -> fail("Not rejected"));
        assertEquals(0, work.get());
        queued.get().run();
        assertEquals(1, work.get());
    }

    @Test public void workExceptionIsNotMisclassifiedOrSwallowed() {
        RejectedExecutionException expected = new RejectedExecutionException("inside callback");
        try {
            OcrCallbackDispatcher.dispatch(Runnable::run, () -> { throw expected; },
                    error -> fail("Callback error is not admission failure"));
            fail("Callback exception missing");
        } catch (RejectedExecutionException actual) { assertSame(expected, actual); }
    }
}
