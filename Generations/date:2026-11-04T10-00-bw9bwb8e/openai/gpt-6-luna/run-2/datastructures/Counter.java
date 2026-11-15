@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long value;

    public Counter() {
        this(0L);
    }

    public Counter(long initialValue) {
        value = initialValue;
    }

    /**
     * Adds one and returns the new value.
     *
     * @throws ArithmeticException if the counter is already at Long.MAX_VALUE
     */
    public long increment() {
        return add(1L);
    }

    /**
     * Subtracts one and returns the new value.
     *
     * @throws ArithmeticException if the counter is already at Long.MIN_VALUE
     */
    public long decrement() {
        return add(-1L);
    }

    /**
     * Adds delta and returns the new value.
     *
     * @throws ArithmeticException if the result would overflow a long
     */
    public long add(long delta) {
        synchronized (lock) {
            if ((delta > 0 && value > Long.MAX_VALUE - delta)
                    || (delta < 0 && value < Long.MIN_VALUE - delta)) {
                throw new ArithmeticException("Counter overflow");
            }
            value += delta;
            return value;
        }
    }

    public long getValue() {
        synchronized (lock) {
            return value;
        }
    }

    public long getCount() {
        return getValue();
    }

    public long get() {
        return getValue();
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }
}
