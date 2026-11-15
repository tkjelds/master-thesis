@ConcurrentBoundedCounter.ThreadSafe
public final class ConcurrentBoundedCounter {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final long minValue;
    private final long maxValue;
    private long value; // guarded by lock

    /**
     * Creates a counter whose value is always kept within [minValue, maxValue].
     *
     * @param minValue     inclusive lower bound
     * @param maxValue     inclusive upper bound
     * @param initialValue starting value, must lie within the bounds
     * @throws IllegalArgumentException if the bounds are inconsistent or the
     *                                  initial value is outside them
     */
    public ConcurrentBoundedCounter(long minValue, long maxValue, long initialValue) {
        if (minValue > maxValue) {
            throw new IllegalArgumentException("minValue must not exceed maxValue");
        }
        if (initialValue < minValue || initialValue > maxValue) {
            throw new IllegalArgumentException("initialValue must lie within [minValue, maxValue]");
        }
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.value = initialValue;
    }

    /**
     * Returns the current value. The read is performed under the monitor, so
     * the caller always observes a value that was fully written by some
     * completed operation.
     */
    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public long getMin() {
        return minValue;
    }

    public long getMax() {
        return maxValue;
    }

    /**
     * Increments the counter. If the counter is at its maximum, the calling
     * thread blocks until another thread decrements it or the thread is
     * interrupted (in which case InterruptedException is thrown and the
     * counter is left unchanged).
     */
    public void increment() throws InterruptedException {
        synchronized (lock) {
            while (value >= maxValue) {
                lock.wait();
            }
            value++;
            lock.notifyAll();
        }
    }

    /**
     * Decrements the counter. If the counter is at its minimum, the calling
     * thread blocks until another thread increments it or the thread is
     * interrupted (in which case InterruptedException is thrown and the
     * counter is left unchanged).
     */
    public void decrement() throws InterruptedException {
        synchronized (lock) {
            while (value <= minValue) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
        }
    }

    /**
     * Non-blocking increment. Returns true if the counter was incremented,
     * or false if it was already at its maximum.
     */
    public boolean tryIncrement() {
        synchronized (lock) {
            if (value >= maxValue) {
                return false;
            }
            value++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Non-blocking decrement. Returns true if the counter was decremented,
     * or false if it was already at its minimum.
     */
    public boolean tryDecrement() {
        synchronized (lock) {
            if (value <= minValue) {
                return false;
            }
            value--;
            lock.notifyAll();
            return true;
        }
    }
}
