@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private volatile long value;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        value = initialValue;
    }

    public long get() {
        synchronized (this) {
            return value;
        }
    }

    // Arithmetic follows Java long semantics, including wraparound on overflow.
    // Operations wait only to acquire the intrinsic monitor, never for a value.
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

    public void reset() {
        synchronized (this) {
            value = 0L;
        }
    }
}
