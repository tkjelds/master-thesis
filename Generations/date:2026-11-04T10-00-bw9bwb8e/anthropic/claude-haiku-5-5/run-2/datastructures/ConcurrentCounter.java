/**
 * A thread-safe bounded counter whose value always lies in the closed range [0, maximum].
 *
 * Blocking policy:
 *  - increment() blocks while the counter is at maximum, until a decrement or reset occurs.
 *  - decrement() blocks while the counter is at zero, until an increment occurs.
 *  - tryIncrement() and tryDecrement() never block; they return false if the operation
 *    cannot complete immediately.
 *  - Blocking operations throw InterruptedException if the waiting thread is interrupted,
 *    in which case the counter is left unchanged.
 *
 * Invariants:
 *  - 0 <= value <= maximum at all times (observed under the lock).
 *  - Every state change is performed while holding the monitor and is followed by notifyAll,
 *    so waiting threads whose conditions became true are woken.
 *  - All waits are guarded by while loops to handle spurious wakeups.
 */
@ConcurrentCounter.ThreadSafe
public final class ConcurrentCounter {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final long maximum;
    private long value; // guarded by lock

    public ConcurrentCounter() {
        this(Long.MAX_VALUE);
    }

    public ConcurrentCounter(long maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("maximum must be positive: " + maximum);
        }
        this.maximum = maximum;
        this.value = 0L;
    }

    /**
     * Increments the counter, blocking while it is at maximum.
     */
    public void increment() throws InterruptedException {
        synchronized (lock) {
            while (value >= maximum) {
                lock.wait();
            }
            value++;
            lock.notifyAll();
        }
    }

    /**
     * Decrements the counter, blocking while it is at zero.
     */
    public void decrement() throws InterruptedException {
        synchronized (lock) {
            while (value <= 0L) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
        }
    }

    /**
     * Attempts to increment without blocking.
     *
     * @return true if the counter was incremented, false if it was at maximum
     */
    public boolean tryIncrement() {
        synchronized (lock) {
            if (value >= maximum) {
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
     * @return true if the counter was decremented, false if it was at zero
     */
    public boolean tryDecrement() {
        synchronized (lock) {
            if (value <= 0L) {
                return false;
            }
            value--;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Resets the counter to zero, waking any threads blocked on increment.
     */
    public void reset() {
        synchronized (lock) {
            value = 0L;
            lock.notifyAll();
        }
    }

    /**
     * Returns a consistent snapshot of the current value.
     */
    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    /**
     * Returns the maximum allowed value (immutable, safely published via final).
     */
    public long getMaximum() {
        return maximum;
    }
}
