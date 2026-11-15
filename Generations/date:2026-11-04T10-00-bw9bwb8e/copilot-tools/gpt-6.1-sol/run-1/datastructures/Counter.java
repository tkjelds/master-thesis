@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private long value;

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

    // Arithmetic uses Java's standard long overflow behavior.
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
}
