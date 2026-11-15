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

    public long getValue() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset(long newValue) {
        synchronized (lock) {
            value = newValue;
        }
    }
}
