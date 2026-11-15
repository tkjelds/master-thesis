/**
 * A thread-safe signed counter. Arithmetic wraps on long overflow.
 * Operations never wait for a particular counter value.
 */
@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private volatile long count;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        count = initialValue;
    }

    /** Increments the counter and returns the new value. */
    public long increment() {
        synchronized (lock) {
            return ++count;
        }
    }

    /** Decrements the counter and returns the new value. */
    public long decrement() {
        synchronized (lock) {
            return --count;
        }
    }

    /** Returns the current value. */
    public long getCount() {
        synchronized (lock) {
            return count;
        }
    }

    /** Sets the counter to zero. */
    public void reset() {
        synchronized (lock) {
            count = 0L;
        }
    }
}
