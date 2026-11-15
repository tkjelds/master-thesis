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
        return add(1L);
    }

    public long decrement() {
        return add(-1L);
    }

    public long add(long amount) {
        synchronized (lock) {
            if ((amount > 0 && value > Long.MAX_VALUE - amount)
                    || (amount < 0 && value < Long.MIN_VALUE - amount)) {
                throw new ArithmeticException("Counter overflow");
            }

            value += amount;
            return value;
        }
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0L;
        }
    }
}
