@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}

    private static final class Monitor {}

    private final Monitor monitor = new Monitor();
    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        value = initialValue;
    }

    public long get() {
        synchronized (monitor) {
            return value;
        }
    }

    // Arithmetic wraps on overflow, as with Java long arithmetic.
    public long add(long delta) {
        synchronized (monitor) {
            value += delta;
            return value;
        }
    }

    public long increment() {
        return add(1L);
    }

    public long decrement() {
        return add(-1L);
    }

    public void reset() {
        synchronized (monitor) {
            value = 0L;
        }
    }
}
