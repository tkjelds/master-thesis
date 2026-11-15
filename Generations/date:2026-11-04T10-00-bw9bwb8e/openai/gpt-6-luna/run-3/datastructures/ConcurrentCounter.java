@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        value = initialValue;
    }

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

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }

    public void reset(long newValue) {
        synchronized (lock) {
            value = newValue;
        }
    }
}
