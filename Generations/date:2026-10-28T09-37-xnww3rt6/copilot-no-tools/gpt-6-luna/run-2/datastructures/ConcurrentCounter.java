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
            value++;
            return value;
        }
    }

    public long decrement() {
        synchronized (lock) {
            value--;
            return value;
        }
    }

    public long add(long amount) {
        synchronized (lock) {
            value += amount;
            return value;
        }
    }

    public long getValue() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }

    public void setValue(long newValue) {
        synchronized (lock) {
            value = newValue;
        }
    }
}
