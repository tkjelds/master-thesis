/**
 * A thread-safe counter initially set to zero.
 * Arithmetic follows Java's signed long wraparound semantics.
 * Operations never wait for a counter value, only for the monitor.
 */
@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private long value;

    /** Increments the counter and returns its new value. */
    public long increment() {
        synchronized (this) {
            return ++value;
        }
    }

    /** Decrements the counter and returns its new value. */
    public long decrement() {
        synchronized (this) {
            return --value;
        }
    }

    /** Returns the current value. */
    public long get() {
        synchronized (this) {
            return value;
        }
    }

    /** Resets the counter to zero. */
    public void reset() {
        synchronized (this) {
            value = 0L;
        }
    }
}
