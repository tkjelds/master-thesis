@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}


    /*
     * Blocking policy:
     *  - increment() blocks while the counter is at its maximum bound and
     *    resumes once a decrement (or reset) makes room.
     *  - decrement() blocks while the counter is at its minimum bound and
     *    resumes once an increment (or reset) makes room.
     *  - tryIncrement() / tryDecrement() never block; they return false
     *    when the operation cannot complete immediately.
     *  - reset(v) never blocks; it throws IllegalArgumentException if v is
     *    outside the configured bounds.
     *  - Blocking methods throw InterruptedException if the calling thread
     *    is interrupted while waiting.
     *
     * All state is guarded by 'lock'. Waits are guarded by while loops and
     * every state change that can unblock a waiter calls notifyAll().
     */

    private final Object lock = new Object();
    private final long minValue;
    private final long maxValue;
    private long value; // guarded by lock

    /** Creates an unbounded-in-practice counter starting at 0 (range is the full long range). */
    public ConcurrentCounter() {
        this(0L, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /** Creates a counter with the given initial value and bounds. */
    public ConcurrentCounter(long initialValue, long minValue, long maxValue) {
        if (minValue > maxValue) {
            throw new IllegalArgumentException("minValue must be <= maxValue");
        }
        if (initialValue < minValue || initialValue > maxValue) {
            throw new IllegalArgumentException("initialValue out of bounds");
        }
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.value = initialValue;
    }

    public long getMinValue() {
        return minValue;
    }

    public long getMaxValue() {
        return maxValue;
    }

    /** Returns the current value. */
    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    /**
     * Increments the counter by one, blocking while it is at the maximum bound.
     *
     * @return the value after the increment
     * @throws InterruptedException if interrupted while waiting
     */
    public long increment() throws InterruptedException {
        synchronized (lock) {
            while (value >= maxValue) {
                lock.wait();
            }
            value++;
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Decrements the counter by one, blocking while it is at the minimum bound.
     *
     * @return the value after the decrement
     * @throws InterruptedException if interrupted while waiting
     */
    public long decrement() throws InterruptedException {
        synchronized (lock) {
            while (value <= minValue) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
            return value;
        }
    }

    /**
     * Attempts to increment without blocking.
     *
     * @return true if the increment was performed, false if at the maximum bound
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
     * Attempts to decrement without blocking.
     *
     * @return true if the decrement was performed, false if at the minimum bound
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

    /**
     * Sets the counter to a new value within bounds. Never blocks.
     *
     * @throws IllegalArgumentException if newValue is outside [minValue, maxValue]
     */
    public void reset(long newValue) {
        if (newValue < minValue || newValue > maxValue) {
            throw new IllegalArgumentException("newValue out of bounds");
        }
        synchronized (lock) {
            value = newValue;
            lock.notifyAll();
        }
    }

    /**
     * Atomically sets the counter to updated only if it currently equals expected.
     * Never blocks.
     *
     * @return true if the value was updated
     * @throws IllegalArgumentException if updated is outside the bounds
     */
    public boolean compareAndSet(long expected, long updated) {
        if (updated < minValue || updated > maxValue) {
            throw new IllegalArgumentException("updated value out of bounds");
        }
        synchronized (lock) {
            if (value != expected) {
                return false;
            }
            value = updated;
            lock.notifyAll();
            return true;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "ConcurrentCounter[value=" + value
                    + ", min=" + minValue
                    + ", max=" + maxValue + "]";
        }
    }
}
