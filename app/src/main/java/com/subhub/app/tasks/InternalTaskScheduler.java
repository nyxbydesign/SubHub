package com.subhub.app.tasks;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Generation-bound scheduling only. The host owns one serial data broker, WorkManager and notices.
 * No Context, owner store, controller unlock, protection session or runtime lease is acquired here.
 */
public final class InternalTaskScheduler implements AutoCloseable {
    public static final long FOREGROUND_INTERVAL_MILLIS = 30_000L;
    public static final long BACKGROUND_INTERVAL_MINUTES = 15L;
    public static final String UNIQUE_WORK_NAME = "subhub-internal-task-deadlines";

    public interface Ticket { void cancel(); }
    public interface Scheduling {
        /** Immediate first run, then fixed delay. Returned cancellation must not interrupt admitted data work. */
        Ticket foreground(Runnable wake, long intervalMillis);
        /** Idempotent for one generation; replace only this unique work when its generation changes. */
        void background(String uniqueName, String generation, long intervalMinutes);
        void cancelBackground(String uniqueName);
    }
    public interface TaskWork {
        /** Enqueue only. Broker rereads exact visible generation and Tasks policy at worker admission. */
        void request(String expectedGeneration);
    }
    public interface Notices {
        /** Includes accepted but not yet visible notifications; never cancel another owner's tags. */
        void cancelGeneration(String generation);
    }
    private static final class Activation {
        final String generation;
        Activation(String generation) { this.generation = generation; }
    }
    private final Runnable requireSerialOwner;
    private final Scheduling scheduling;
    private final TaskWork work;
    private final Notices notices;
    private final Set<String> retiredNotices = new LinkedHashSet<>();
    private volatile Activation active;
    private Ticket foreground;
    private volatile Object foregroundGeneration;
    private int started;
    private boolean closed;

    public InternalTaskScheduler(Runnable requireSerialOwner, Scheduling scheduling, TaskWork work, Notices notices) {
        this.requireSerialOwner = Objects.requireNonNull(requireSerialOwner);
        this.scheduling = Objects.requireNonNull(scheduling);
        this.work = Objects.requireNonNull(work);
        this.notices = Objects.requireNonNull(notices);
    }
    /** Called on the sole broker after durable visibility/policy/boundary acknowledgement.
     * Null means explicitly disabled/hidden; unavailable or unknown legacy data must be reported,
     * not translated to null as if it were an acknowledged disablement. */
    public void reconcile(String visibleGeneration) {
        requireSerialOwner.run();
        if (closed) throw new IllegalStateException("Internal task scheduler is closed");
        if (visibleGeneration != null && !UUID.fromString(visibleGeneration).toString().equals(visibleGeneration))
            throw new IllegalArgumentException("Invalid internal task generation");
        Activation previous = active;
        boolean changed = !Objects.equals(previous == null ? null : previous.generation, visibleGeneration);
        if (changed) {
            // Invalidate old callbacks before cancellation, which may throw or leave dispatched work queued.
            active = visibleGeneration == null ? null : new Activation(visibleGeneration);
            if (previous != null) retiredNotices.add(previous.generation);
            cancelForeground();
        }
        if (foreground != null && foregroundGeneration == null) cancelForeground();
        clearRetiredNotices();
        Activation current = active;
        if (current == null) scheduling.cancelBackground(UNIQUE_WORK_NAME);
        else {
            scheduling.background(UNIQUE_WORK_NAME, current.generation, BACKGROUND_INTERVAL_MINUTES);
            startForeground(current);
        }
    }
    public void activityStarted() {
        requireSerialOwner.run();
        if (closed) return;
        started = Math.addExact(started, 1);
        if (active != null) startForeground(active);
    }
    public void activityStopped() {
        requireSerialOwner.run();
        if (closed || started == 0) return;
        if (--started == 0) cancelForeground();
    }
    private void startForeground(Activation expected) {
        if (foreground != null && foregroundGeneration == null) cancelForeground();
        if (started == 0 || foreground != null) return;
        Object timer = new Object(); foregroundGeneration = timer;
        foreground = scheduling.foreground(() -> {
            if (active == expected && foregroundGeneration == timer) work.request(expected.generation);
        }, FOREGROUND_INTERVAL_MILLIS);
        if (foreground == null) throw new IllegalStateException("Missing internal task timer ticket");
    }
    private void cancelForeground() {
        foregroundGeneration = null;
        Ticket old = foreground;
        if (old != null) { old.cancel(); foreground = null; }
    }
    private void clearRetiredNotices() {
        java.util.Iterator<String> iterator = retiredNotices.iterator();
        while (iterator.hasNext()) {
            notices.cancelGeneration(iterator.next());
            iterator.remove(); // A failed cancellation retains its exact generation for retry.
        }
    }
    /** Explicit changed-data wake. A controller lock or protection stop does not disable task processing. */
    public void changed(String generation) {
        Activation current = active;
        if (current != null && current.generation.equals(generation)) work.request(generation);
    }
    @Override public void close() {
        requireSerialOwner.run();
        Activation previous = active; active = null; closed = true;
        if (previous != null) retiredNotices.add(previous.generation);
        cancelForeground(); clearRetiredNotices(); scheduling.cancelBackground(UNIQUE_WORK_NAME);
    }
}
