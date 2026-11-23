package com.xxxk2020.zssteink;

/** Uses monotonic elapsed time supplied by the caller; independent of Android for testing. */
final class ImportCheckpoint {
    private static final long INTERVAL_MS = 1000;
    private long lastWrite = -1;

    boolean shouldWrite(long now, boolean force) {
        if (!force && lastWrite >= 0 && now >= lastWrite && now - lastWrite < INTERVAL_MS) return false;
        lastWrite = now;
        return true;
    }

    void reset() { lastWrite = -1; }
}
