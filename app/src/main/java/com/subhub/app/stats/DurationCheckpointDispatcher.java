package com.subhub.app.stats;

import java.util.concurrent.Executor;

/** Legacy screen-checkpoint representation retained for migration compatibility tests.
 * Production duration accounting uses persisted session facts, not this dispatcher. */
final class DurationCheckpointDispatcher {
    interface Clock { long wall(); long elapsed(); }
    interface Source { Snapshot capture(long wall, long elapsed); }
    interface Sink { void write(Snapshot snapshot); }

    static final class Snapshot {
        final long session, wall, elapsed;
        final String zone;

        Snapshot(long session, boolean bound, boolean interactive, boolean locked,
                long wall, long elapsed, String zone) {
            this.session = bound && interactive && !locked ? session : 0;
            this.wall = wall;
            this.elapsed = elapsed;
            this.zone = zone;
        }
    }

    private final Executor executor;
    private final Clock clock;
    private final Source source;
    private final Sink sink;

    DurationCheckpointDispatcher(Executor executor, Clock clock, Source source, Sink sink) {
        this.executor = executor;
        this.clock = clock;
        this.source = source;
        this.sink = sink;
    }

    synchronized void checkpoint() {
        checkpoint(clock.wall(), clock.elapsed());
    }

    synchronized void checkpoint(long wall, long elapsed) {
        Snapshot snapshot = source.capture(wall, elapsed);
        executor.execute(() -> sink.write(snapshot));
    }
}
