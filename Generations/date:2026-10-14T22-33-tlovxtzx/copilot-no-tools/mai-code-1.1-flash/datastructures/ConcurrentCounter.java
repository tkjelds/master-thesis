@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
    @interface ThreadSafe {}

    /*
     * Policy:
     * - The counter is non-negative.
     * - increment() and addAndGet() never block.
     * - decrement() and addAndGet(delta) with negative delta block until the
     *   requested operation can complete without making the counter negative.
     * - awaitZero() blocks until the counter reaches zero.
     * All blocking operations wait on the monitor in while-loops and notifyAll
     * when state changes.
     */
    private final Object monitor = new Object();
    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        if (initialValue < 0L) {
            throw new IllegalArgumentException("Counter value cannot be negative.");
        }
        this.value = initialValue;
    }

    public long get() {
        synchronized (monitor) {
            return value;
        }
    }

    public boolean isZero() {
        synchronized (monitor) {
            return value == 0L;
        }
    }

    public long increment() {
        synchronized (monitor) {
            value++;
            monitor.notifyAll();
            return value;
        }
    }

    public long incrementAndGet() {
        return increment();
    }

    public long decrement() throws InterruptedException {
        synchronized (monitor) {
            while (value <= 0L) {
                monitor.wait();
            }
            value--;
            monitor.notifyAll();
            return value;
        }
    }

    public long decrementAndGet() throws InterruptedException {
        return decrement();
    }

    public long addAndGet(long delta) throws InterruptedException {
        synchronized (monitor) {
            while (delta < 0L && value + delta < 0L) {
                monitor.wait();
            }
            value += delta;
            monitor.notifyAll();
            return value;
        }
    }

    public long subtractAndGet(long delta) throws InterruptedException {
        return addAndGet(-delta);
    }

    public void set(long newValue) {
        if (newValue < 0L) {
            throw new IllegalArgumentException("Counter value cannot be negative.");
        }
        synchronized (monitor) {
            value = newValue;
            monitor.notifyAll();
        }
    }

    public boolean compareAndSet(long expectedValue, long newValue) {
        if (newValue < 0L) {
            throw new IllegalArgumentException("Counter value cannot be negative.");
        }
        synchronized (monitor) {
            if (value == expectedValue) {
                value = newValue;
                monitor.notifyAll();
                return true;
            }
            return false;
        }
    }

    public long awaitZero() throws InterruptedException {
        synchronized (monitor) {
            while (value != 0L) {
                monitor.wait();
            }
            return value;
        }
    }

    public long awaitAtLeast(long threshold) throws InterruptedException {
        synchronized (monitor) {
            while (value < threshold) {
                monitor.wait();
            }
            return value;
        }
    }
}
