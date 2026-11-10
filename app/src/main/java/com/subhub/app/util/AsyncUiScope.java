package com.subhub.app.util;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A page owns its jobs. Replace stale requests, deliver while started, and release callbacks on
 * destruction. Work closures must read application data rather than capture views/activities.
 */
public final class AsyncUiScope implements DefaultLifecycleObserver, AutoCloseable {
    private static final Map<LifecycleOwner, WeakReference<AsyncUiScope>> pages =
            new WeakHashMap<>();
    private final WeakReference<LifecycleOwner> owner;
    private final ExecutorService worker;
    private final String workerName;
    private ExecutorService serialWorker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> jobs = new LinkedHashMap<>();
    private final ConcurrentMap<Long, Completed> deliveries = new ConcurrentHashMap<>();
    private long sequence;
    private volatile boolean closed;

    @MainThread
    public AsyncUiScope(LifecycleOwner owner, String threadName) {
        this.owner = new WeakReference<>(owner);
        workerName = threadName;
        ThreadPoolExecutor pool =
                new ThreadPoolExecutor(
                        2,
                        2,
                        15,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        task -> new Thread(task, threadName));
        pool.allowCoreThreadTimeOut(true);
        worker = pool;
        owner.getLifecycle().addObserver(this);
    }

    @MainThread
    public static AsyncUiScope forPage(LifecycleOwner owner) {
        WeakReference<AsyncUiScope> reference = pages.get(owner);
        AsyncUiScope scope = reference == null ? null : reference.get();
        if (scope == null || scope.closed) {
            scope = new AsyncUiScope(owner, "subhub-ui-" + owner.getClass().getSimpleName());
            pages.put(owner, new WeakReference<>(scope));
        }
        return scope;
    }

    @MainThread
    public static AsyncUiScope forContext(android.content.Context context) {
        for (int depth = 0; depth < 32; depth++) {
            if (context instanceof LifecycleOwner) return forPage((LifecycleOwner) context);
            if (!(context instanceof android.content.ContextWrapper)) break;
            android.content.Context parent =
                    ((android.content.ContextWrapper) context).getBaseContext();
            if (parent == context) break;
            context = parent;
        }
        throw new IllegalArgumentException("Async UI work requires a page lifecycle");
    }

    @MainThread
    public boolean isPending(String key) {
        return jobs.containsKey(key);
    }

    @MainThread
    public void cancel(String key) {
        Pending old = jobs.remove(key);
        if (old != null) {
            if (old.future != null) old.future.cancel(true);
            if (old.ready) discard(old.value);
        }
    }

    @MainThread
    public void cancelPrefix(String prefix) {
        for (String key : new ArrayList<>(jobs.keySet())) if (key.startsWith(prefix)) cancel(key);
    }

    @MainThread
    @SuppressWarnings("unchecked")
    public <T> void load(
            String key, Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        enqueue(worker, key, work, success, failure, true);
    }

    /** Keep accepted UI mutations in order and let them finish after the page closes. */
    @MainThread
    public <T> void loadSerial(
            String key, Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        if (closed) return;
        if (serialWorker == null) {
            String name = workerName + "-writes";
            serialWorker = Executors.newSingleThreadExecutor(task -> new Thread(task, name));
        }
        enqueue(serialWorker, key, work, success, failure, false);
    }

    @SuppressWarnings("unchecked")
    private <T> void enqueue(
            ExecutorService executor,
            String key,
            Callable<T> work,
            Consumer<T> success,
            Consumer<Exception> failure,
            boolean cancelOnClose) {
        if (closed) return;
        cancel(key);
        long token = ++sequence;
        Pending pending =
                new Pending(token, value -> success.accept((T) value), failure, cancelOnClose);
        jobs.put(key, pending);
        // The worker holds the scope/token and application work, never the callback record.
        pending.future =
                executor.submit(
                        () -> {
                            T result = null;
                            Exception error = null;
                            try {
                                result = work.call();
                            } catch (Exception problem) {
                                error = problem;
                            }
                            Completed completed = new Completed(result, error);
                            if (closed) {
                                discard(result);
                                return;
                            }
                            deliveries.put(token, completed);
                            if (closed && deliveries.remove(token, completed)) {
                                discard(result);
                                return;
                            }
                            main.post(() -> complete(key, token));
                        });
    }

    private void complete(String key, long token) {
        Completed completed = deliveries.remove(token);
        if (completed == null) return;
        Object value = completed.value;
        Exception error = completed.error;
        Pending pending = jobs.get(key);
        if (closed || pending == null || pending.token != token) {
            discard(value);
            return;
        }
        pending.value = value;
        pending.error = error;
        pending.ready = true;
        pending.future = null;
        deliver(key, pending);
    }

    private void deliver(String key, Pending pending) {
        LifecycleOwner page = owner.get();
        if (page == null || page.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
            close();
            return;
        }
        if (!pending.ready
                || !page.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
            return;
        if (jobs.get(key) != pending) return;
        jobs.remove(key);
        if (pending.error == null) pending.success.accept(pending.value);
        else pending.failure.accept(pending.error);
    }

    @Override
    public void onStart(LifecycleOwner owner) {
        for (Map.Entry<String, Pending> entry : new ArrayList<>(jobs.entrySet()))
            deliver(entry.getKey(), entry.getValue());
    }

    @Override
    public void onDestroy(LifecycleOwner owner) {
        close();
    }

    @Override
    @MainThread
    public void close() {
        if (closed) return;
        closed = true;
        for (String key : new ArrayList<>(jobs.keySet())) {
            Pending pending = jobs.remove(key);
            if (pending.cancelOnClose && pending.future != null) pending.future.cancel(true);
            if (pending.ready) discard(pending.value);
        }
        for (Long token : new ArrayList<>(deliveries.keySet())) {
            Completed completed = deliveries.remove(token);
            if (completed != null) discard(completed.value);
        }
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (serialWorker != null) serialWorker.shutdown();
    }

    private static void discard(Object value) {
        if (value instanceof AutoCloseable)
            try {
                ((AutoCloseable) value).close();
            } catch (Exception ignored) {
                android.util.Log.w("AsyncUi", "Could not release discarded UI data");
            }
    }

    private static final class Pending {
        final long token;
        final boolean cancelOnClose;
        final Consumer<Object> success;
        final Consumer<Exception> failure;
        Future<?> future;
        Object value;
        Exception error;
        boolean ready;

        Pending(
                long token,
                Consumer<Object> success,
                Consumer<Exception> failure,
                boolean cancelOnClose) {
            this.token = token;
            this.success = success;
            this.failure = failure;
            this.cancelOnClose = cancelOnClose;
        }
    }

    private static final class Completed {
        final Object value;
        final Exception error;

        Completed(Object value, Exception error) {
            this.value = value;
            this.error = error;
        }
    }
}
