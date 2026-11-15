@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}

    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        value = initialValue;
    }

    public long increment() {
        synchronized (this) {
            return ++value;
        }
    }

    public long decrement() {
        synchronized (this) {
            return --value;
        }
    }

    public long add(long delta) {
        synchronized (this) {
            value += delta;
            return value;
        }
    }

    public long get() {
        synchronized (this) {
            return value;
        }
    }

    public void reset(long newValue) {
        synchronized (this) {
            value = newValue;
        }
    }
}
