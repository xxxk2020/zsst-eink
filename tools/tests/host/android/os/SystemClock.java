package android.os;

/** A virtual monotonic clock: tests never sleep or depend on machine speed. */
public final class SystemClock {
    private static long now;
    public static long elapsedRealtime() { return now; }
    public static void setElapsedRealtime(long value) { now = value; }
}
