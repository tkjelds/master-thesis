/**
 * A thread-safe counter. Arithmetic wraps on long overflow.
 * Operations do not wait for a particular counter value, but may block
 * briefly while another operation holds the counter's monitor.
 */
@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private static final class Monitor {}

    private final Monitor monitor = new Monitor();
    private long value;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        value = initialValue;
    }

    public long get() {
        synchronized (monitor) {
            return value;
        }
    }

    public void set(long newValue) {
        synchronized (monitor) {
            value = newValue;
        }
    }

    public long getAndSet(long newValue) {
        synchronized (monitor) {
            long previous = value;
            value = newValue;
            return previous;
        }
    }

    public long addAndGet(long delta) {
        synchronized (monitor) {
            value += delta;
            return value;
        }
    }

    public long getAndAdd(long delta) {
        synchronized (monitor) {
            long previous = value;
            value += delta;
            return previous;
        }
    }

    public long incrementAndGet() {
        return addAndGet(1L);
    }

    public long decrementAndGet() {
        return addAndGet(-1L);
    }

    public long getAndIncrement() {
        return getAndAdd(1L);
    }

    public long getAndDecrement() {
        return getAndAdd(-1L);
    }

    public void reset() {
        set(0L);
    }
}
