package com.subhub.app.detection.text;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.android.gms.tasks.TaskCompletionSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Google Tasks callbacks after shutdown, including the main-thread cancellation path. */
@RunWith(AndroidJUnit4.class)
public final class OcrCallbackLifecycleAndroidTest {
    @Test public void successAfterShutdownOnlyCleansUp() throws Exception { afterShutdown(0); }
    @Test public void failureAfterShutdownOnlyCleansUp() throws Exception { afterShutdown(1); }
    @Test public void cancellationAfterShutdownOnlyCleansUp() throws Exception { afterShutdown(2); }

    private static void afterShutdown(int outcome) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.shutdown();
        assertTrue(worker.awaitTermination(1, TimeUnit.SECONDS));
        CancellationTokenSource cancellation = new CancellationTokenSource();
        TaskCompletionSource<String> source = new TaskCompletionSource<>(cancellation.getToken());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger mapped = new AtomicInteger(), cleaned = new AtomicInteger();
        source.getTask().addOnCompleteListener(Runnable::run, task ->
                OcrCallbackDispatcher.dispatch(worker, mapped::incrementAndGet, error -> {
                    cleaned.incrementAndGet();
                    completed.countDown();
                }));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (outcome == 0) source.setResult("result");
            else if (outcome == 1) source.setException(new IllegalStateException("fixture"));
            else cancellation.cancel();
        });
        assertTrue("Terminal cleanup missing", completed.await(3, TimeUnit.SECONDS));
        assertTrue(source.getTask().isComplete());
        assertEquals(0, mapped.get());
        assertEquals(1, cleaned.get());
    }

    @Test public void warmupCloseDoesNotDependOnStoppedWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.shutdown();
        try (OcrTextSmutDetector detector = new OcrTextSmutDetector(
                new SmutTextClassifier(ApplicationProvider.getApplicationContext()))) {
            detector.warmUp(worker);
            detector.close();
            detector.close();
            detector.warmUp(worker);
            // Let the actual bundled recognizer cancellation reach Google's main-thread queue.
            Thread.sleep(1000);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }
}
