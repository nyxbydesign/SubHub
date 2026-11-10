package com.subhub.app.tasks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class TaskForegroundTickerTest {
    @Test public void backgroundHasNoRepeatingCallback() {
        Fixture fixture = new Fixture();
        assertTrue(fixture.scheduler.entries.isEmpty());
        fixture.scheduler.advance(3_600_000);
        assertEquals(0, fixture.ticks);
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(1, fixture.ticks);
        fixture.ticker.activityStopped();
        fixture.scheduler.advance(3_600_000);
        assertEquals(1, fixture.ticks);
        assertEquals(0, fixture.scheduler.pendingCount());
    }

    @Test public void visibleChecksStartImmediatelyAndKeepThirtySecondCadence() {
        Fixture fixture = new Fixture();
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(1, fixture.ticks);
        fixture.scheduler.advance(29_999);
        assertEquals(1, fixture.ticks);
        fixture.scheduler.advance(1);
        assertEquals(2, fixture.ticks);
        fixture.scheduler.advance(30_000);
        assertEquals(3, fixture.ticks);
        assertEquals(1, fixture.scheduler.pendingCount());
    }

    @Test public void overlappingActivitiesShareOneTimerUntilLastStop() {
        Fixture fixture = new Fixture();
        fixture.ticker.activityStarted();
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(1, fixture.ticks);
        assertEquals(1, fixture.scheduler.entries.size());
        fixture.ticker.activityStopped();
        fixture.scheduler.advance(30_000);
        assertEquals(2, fixture.ticks);
        fixture.ticker.activityStopped();
        assertEquals(0, fixture.scheduler.pendingCount());
    }

    @Test public void rapidStopAndRestartCannotReviveCancelledCallback() {
        Fixture fixture = new Fixture();
        fixture.ticker.activityStarted();
        Entry old = fixture.scheduler.entries.get(0);
        fixture.ticker.activityStopped();
        old.tick.run(); // Simulate delivery despite cancellation, before a new foreground session.
        assertEquals(0, fixture.ticks);
        fixture.ticker.activityStarted();
        old.tick.run(); // The old callback must not borrow the new foreground session.
        assertEquals(0, fixture.ticks);
        fixture.scheduler.advance(0);
        assertEquals(1, fixture.ticks);
        assertEquals(1, fixture.scheduler.pendingCount());
    }

    @Test public void returningAfterLongIdleChecksImmediately() {
        Fixture fixture = new Fixture();
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        fixture.ticker.activityStopped();
        fixture.scheduler.advance(3_600_000);
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(2, fixture.ticks);
        fixture.scheduler.advance(30_000);
        assertEquals(3, fixture.ticks);
    }

    @Test public void stopInsideAdmittedSweepCancelsRepeatsWithoutInterruptingWork() {
        Scheduler scheduler = new Scheduler();
        TaskForegroundTicker[] owner = new TaskForegroundTicker[1];
        int[] completed = {0};
        owner[0] = new TaskForegroundTicker(scheduler, () -> {
            owner[0].activityStopped();
            completed[0]++;
        });
        owner[0].activityStarted();
        scheduler.advance(0);
        scheduler.advance(60_000);
        assertEquals(1, completed[0]);
        assertEquals(0, scheduler.pendingCount());
    }

    @Test public void unmatchedStopDoesNotPreventNextForegroundStart() {
        Fixture fixture = new Fixture();
        fixture.ticker.activityStopped();
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(1, fixture.ticks);
        fixture.ticker.activityStopped();
        fixture.ticker.activityStopped();
        fixture.ticker.activityStarted();
        fixture.scheduler.advance(0);
        assertEquals(2, fixture.ticks);
        assertEquals(1, fixture.scheduler.pendingCount());
    }

    private static final class Fixture {
        final Scheduler scheduler = new Scheduler();
        int ticks;
        final TaskForegroundTicker ticker = new TaskForegroundTicker(scheduler, () -> ticks++);
    }

    private static final class Entry implements TaskForegroundTicker.Ticket {
        final Runnable tick;
        final long interval;
        long due;
        boolean cancelled;
        Entry(Runnable tick, long interval, long due) {
            this.tick = tick; this.interval = interval; this.due = due;
        }
        @Override public void cancel() { cancelled = true; }
    }

    private static final class Scheduler implements TaskForegroundTicker.Scheduler {
        final List<Entry> entries = new ArrayList<>();
        long now;
        @Override public TaskForegroundTicker.Ticket repeat(Runnable tick, long delayMillis) {
            Entry entry = new Entry(tick, delayMillis, now);
            entries.add(entry);
            return entry;
        }
        void advance(long millis) {
            long through = now + millis;
            while (true) {
                Entry next = null;
                for (Entry entry : entries) if (!entry.cancelled && entry.due <= through
                        && (next == null || entry.due < next.due)) next = entry;
                if (next == null) break;
                now = next.due;
                next.tick.run();
                next.due = now + next.interval;
            }
            now = through;
        }
        int pendingCount() {
            int count = 0;
            for (Entry entry : entries) if (!entry.cancelled) count++;
            return count;
        }
    }
}
