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

    public long incrementAndGet() {
        synchronized (lock) {
            return ++value;
        }
    }

    public long decrementAndGet() {
        synchronized (lock) {
            return --value;
        }
    }

    public long addAndGet(long delta) {
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

    public void set(long newValue) {
        synchronized (lock) {
            value = newValue;
        }
    }
}
