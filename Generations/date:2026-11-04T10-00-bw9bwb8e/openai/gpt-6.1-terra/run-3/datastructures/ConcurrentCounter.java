@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}

    private static final class Lock {
    }

    private final Lock lock = new Lock();
    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        value = initialValue;
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    // Arithmetic wraps around according to Java's long overflow rules.
    public long increment() {
        synchronized (lock) {
            return ++value;
        }
    }

    public long decrement() {
        synchronized (lock) {
            return --value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }
}
