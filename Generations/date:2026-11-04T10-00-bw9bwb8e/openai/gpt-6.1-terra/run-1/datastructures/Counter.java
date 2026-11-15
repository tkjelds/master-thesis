@Counter.ThreadSafe
public class Counter {
    @interface ThreadSafe {}

    private static final class Lock {}

    private final Lock lock = new Lock();
    private long value;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        value = initialValue;
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    // Arithmetic wraps on overflow, as with ordinary Java long arithmetic.
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

    public long add(long delta) {
        synchronized (lock) {
            value += delta;
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }
}
