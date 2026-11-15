@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
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

    public long getAndIncrement() {
        synchronized (lock) {
            return value++;
        }
    }

    public long getAndDecrement() {
        synchronized (lock) {
            return value--;
        }
    }

    public long getAndAdd(long delta) {
        synchronized (lock) {
            long previous = value;
            value += delta;
            return previous;
        }
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public long getCount() {
        synchronized (lock) {
            return value;
        }
    }

    public void set(long newValue) {
        synchronized (lock) {
            value = newValue;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }
}
