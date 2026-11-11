package com.subhub.app.censor.android;

/**
 * Session-local accounting identity only. Geometry/tracker IDs are never changed. A new epoch is
 * required whenever a tracker is cleared/replaced; old queued events already carry copied IDs.
 * The factory retains one instance for the logical owner session, including source replacements.
 */
public final class LegacyTrackIds {
    private int base, high;
    private long serial;
    private Epoch current;

    public synchronized Epoch nextEpoch() {
        base = Math.addExact(base, high);
        high = 0;
        serial = Math.incrementExact(serial);
        return current = new Epoch(serial);
    }
    public final class Epoch {
        public final long serial;
        private Epoch(long serial) { this.serial = serial; }
        /** Positive legacy IDs are monotonic within a tracker epoch; gaps need no retained map. */
        public int accountingId(int localId) {
            synchronized (LegacyTrackIds.this) {
                if (current != this) throw new IllegalStateException("Retired tracker epoch");
                if (localId <= 0) throw new IllegalArgumentException("Invalid legacy track identity");
                int result = Math.addExact(base, localId);
                high = Math.max(high, localId);
                return result;
            }
        }
    }
}
