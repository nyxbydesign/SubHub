package com.subhub.app.stats;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DurationCheckpointDispatcherAndroidTest {
    @Test public void actualDispatcherPreservesOffOnBoundariesAcrossBacklogStaleSamplesAndRestart() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "duration-boundary-" + UUID.randomUUID() + ".db";
        long start = Instant.parse("2026-11-05T12:00:00Z").toEpochMilli();
        Queue<Runnable> queue = new ArrayDeque<>();
        boolean[] interactive = {true};
        long[] session = {7}, now = {0};
        try (DailyStatsStore store = new DailyStatsStore(context, name)) {
            DurationCheckpointDispatcher.Clock clock = new DurationCheckpointDispatcher.Clock() {
                public long wall() { return start + now[0]; }
                public long elapsed() { return now[0]; }
            };
            DurationCheckpointDispatcher.Source source = (wall, elapsed) ->
                    new DurationCheckpointDispatcher.Snapshot(session[0], true, interactive[0],
                            false, wall, elapsed, "UTC");
            DurationCheckpointDispatcher dispatcher = new DurationCheckpointDispatcher(queue::add,
                    clock, source, sample -> store.heartbeat("first", sample.session,
                            sample.wall, sample.elapsed, sample.zone));
            dispatcher.checkpoint();
            interactive[0] = false;
            dispatcher.checkpoint(start + 1_000, 1_000);
            interactive[0] = true;
            dispatcher.checkpoint(start + 1_010, 1_010);
            now[0] = 2_010;
            dispatcher.checkpoint();
            // This queued stale observation cannot overwrite the newer active baseline.
            interactive[0] = false;
            dispatcher.checkpoint(start + 500, 500);
            interactive[0] = true;
            now[0] = 3_010;
            dispatcher.checkpoint();
            while (!queue.isEmpty()) queue.remove().run();
            assertEquals(3_000, serviceMillis(store));

            interactive[0] = false;
            dispatcher.checkpoint(start + 4_010, 4_010);
            now[0] = 50_000;
            dispatcher.checkpoint();
            while (!queue.isEmpty()) queue.remove().run();
            assertEquals(4_000, serviceMillis(store));

            DurationCheckpointDispatcher restarted = new DurationCheckpointDispatcher(queue::add,
                    clock, source, sample -> store.heartbeat("second", sample.session,
                            sample.wall, sample.elapsed, sample.zone));
            interactive[0] = true;
            now[0] = 100_000;
            restarted.checkpoint();
            now[0] = 101_000;
            restarted.checkpoint();
            session[0] = 8;
            now[0] = 110_000;
            restarted.checkpoint();
            now[0] = 111_000;
            restarted.checkpoint();
            while (!queue.isEmpty()) queue.remove().run();
            assertEquals(6_000, serviceMillis(store));
            DurationCheckpointDispatcher thirdProcess = new DurationCheckpointDispatcher(queue::add,
                    clock, source, sample -> store.heartbeat("third", sample.session,
                            sample.wall, sample.elapsed, sample.zone));
            now[0] = 200_000;
            thirdProcess.checkpoint();
            now[0] = 201_000;
            thirdProcess.checkpoint();
            while (!queue.isEmpty()) queue.remove().run();
            assertEquals(7_000, serviceMillis(store));
        } finally {
            context.deleteDatabase(name);
        }
    }

    private static long serviceMillis(DailyStatsStore store) {
        Map<String, Long> day = store.days("2026-11-05", "2026-11-05").get("2026-11-05");
        return day == null ? 0 : day.getOrDefault("service_ms", 0L);
    }
}
