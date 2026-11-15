@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long count;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        count = initialValue;
    }

    public long increment() {
        synchronized (lock) {
            count++;
            return count;
        }
    }

    public long decrement() {
        synchronized (lock) {
            count--;
            return count;
        }
    }

    public long add(long amount) {
        synchronized (lock) {
            count += amount;
            return count;
        }
    }

    public long getCount() {
        synchronized (lock) {
            return count;
        }
    }

    public void reset() {
        synchronized (lock) {
            count = 0L;
        }
    }

    public void reset(long initialValue) {
        synchronized (lock) {
            count = initialValue;
        }
    }
}
