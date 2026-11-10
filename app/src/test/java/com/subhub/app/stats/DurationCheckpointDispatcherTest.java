package com.subhub.app.stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.junit.Test;
import static org.junit.Assert.*;

public class DurationCheckpointDispatcherTest {
    @Test public void unavailableScreenAndUnboundServiceFailClosedWithoutChangingSessionAuthority() {
        for (boolean bound : new boolean[]{false, true})
            for (boolean interactive : new boolean[]{false, true})
                for (boolean locked : new boolean[]{false, true}) {
                    DurationCheckpointDispatcher.Snapshot snapshot = new DurationCheckpointDispatcher.Snapshot(
                            42, bound, interactive, locked, 100, 200, "UTC");
                    assertEquals(bound && interactive && !locked ? 42 : 0, snapshot.session);
                    assertEquals(100, snapshot.wall);
                    assertEquals(200, snapshot.elapsed);
                }
    }

    @Test public void backloggedOffOnAndPeriodicCapturesKeepTheirOriginalStateAndOrder() {
        Queue<Runnable> queue = new ArrayDeque<>();
        List<DurationCheckpointDispatcher.Snapshot> written = new ArrayList<>();
        boolean[] interactive = {true};
        long[] now = {100};
        DurationCheckpointDispatcher dispatcher = new DurationCheckpointDispatcher(queue::add,
                new DurationCheckpointDispatcher.Clock() {
                    public long wall() { return 1_000 + now[0]; }
                    public long elapsed() { return now[0]; }
                }, (wall, elapsed) -> new DurationCheckpointDispatcher.Snapshot(
                        42, true, interactive[0], false, wall, elapsed, "UTC"), written::add);
        dispatcher.checkpoint();
        interactive[0] = false;
        dispatcher.checkpoint(1_150, 150);
        interactive[0] = true;
        dispatcher.checkpoint(1_160, 160);
        now[0] = 200;
        dispatcher.checkpoint();
        assertTrue(written.isEmpty());
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(4, written.size());
        assertEquals(42, written.get(0).session);
        assertEquals(0, written.get(1).session);
        assertEquals(150, written.get(1).elapsed);
        assertEquals(42, written.get(2).session);
        assertEquals(160, written.get(2).elapsed);
        assertEquals(200, written.get(3).elapsed);
    }
}
