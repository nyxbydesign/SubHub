package com.subhub.app.util;

import static org.junit.Assert.*;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class AsyncUiScopeAndroidTest {
    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    @Test
    public void replacedReadsCannotPublishAndStoppedPagesDeferResults() throws Exception {
        Owner owner = new Owner();
        AsyncUiScope[] scope = new AsyncUiScope[1];
        AtomicInteger shown = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        main(
                () -> {
                    owner.lifecycle.setCurrentState(Lifecycle.State.STARTED);
                    scope[0] = new AsyncUiScope(owner, "scope-fixture");
                    scope[0].load(
                            "read",
                            () -> {
                                entered.countDown();
                                // A non-interruptible provider result must still be discarded after
                                // replacement.
                                while (release.getCount() > 0) {
                                    try {
                                        release.await();
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                                return 1;
                            },
                            shown::set,
                            error -> fail(error.toString()));
                });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        CountDownLatch ready = new CountDownLatch(1);
        main(
                () -> {
                    owner.lifecycle.setCurrentState(Lifecycle.State.CREATED);
                    scope[0].load(
                            "read",
                            () -> {
                                ready.countDown();
                                return 2;
                            },
                            shown::set,
                            error -> fail(error.toString()));
                });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        release.countDown();
        android.os.SystemClock.sleep(80);
        main(() -> assertEquals(0, shown.get()));
        main(() -> owner.lifecycle.setCurrentState(Lifecycle.State.STARTED));
        main(() -> assertEquals(2, shown.get()));
        main(() -> owner.lifecycle.setCurrentState(Lifecycle.State.DESTROYED));
    }

    @Test
    public void destructionDisposesReadResultsAndFinishesAcceptedWritesWithoutCallbacks()
            throws Exception {
        Owner owner = new Owner();
        AsyncUiScope[] scope = new AsyncUiScope[1];
        CountDownLatch readEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1), persisted = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        main(
                () -> {
                    owner.lifecycle.setCurrentState(Lifecycle.State.STARTED);
                    scope[0] = new AsyncUiScope(owner, "scope-fixture");
                    scope[0].load(
                            "read",
                            () -> {
                                readEntered.countDown();
                                while (release.getCount() > 0) {
                                    try {
                                        release.await();
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                                return (AutoCloseable) disposed::countDown;
                            },
                            value -> callbacks.incrementAndGet(),
                            error -> callbacks.incrementAndGet());
                    scope[0].loadSerial(
                            "write-1",
                            () -> {
                                release.await();
                                return null;
                            },
                            value -> callbacks.incrementAndGet(),
                            error -> callbacks.incrementAndGet());
                    scope[0].loadSerial(
                            "write-2",
                            () -> {
                                persisted.countDown();
                                return null;
                            },
                            value -> callbacks.incrementAndGet(),
                            error -> callbacks.incrementAndGet());
                });
        assertTrue(readEntered.await(2, TimeUnit.SECONDS));
        main(() -> owner.lifecycle.setCurrentState(Lifecycle.State.DESTROYED));
        release.countDown();
        assertTrue(disposed.await(2, TimeUnit.SECONDS));
        assertTrue(persisted.await(2, TimeUnit.SECONDS));
        main(() -> assertEquals(0, callbacks.get()));
    }

    private static final class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = LifecycleRegistry.createUnsafe(this);

        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }
    }
}
