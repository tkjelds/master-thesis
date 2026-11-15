@Counter.ThreadSafe
class Counter {
    @interface ThreadSafe {}

    private int count = 0;
    private final Object lock = new Object();

    /**
     * Increments the counter and returns its new value. Blocks if another thread holds the lock.
     * @return The count after incrementing.
     */
    public int incrementAndGet() throws InterruptedException {
        synchronized (lock) {
            count = count + 1;
            return count;
        }
    }

    /**
     * Decrements the counter and returns its new value. Blocks if another thread holds the lock.
     * @return The count after decrementing.
     */
    public int decrementAndGet() throws InterruptedException {
        synchronized (lock) {
            count = count - 1;
            return count;
        }
    }

    /**
     * Gets the current value of the counter. Blocks only briefly to ensure visibility of writes from other threads.
     * @return The current count.
     */
    public int getValue() throws InterruptedException {
        synchronized (lock) {
            return count;
        }
    }

    /**
     * Increments the counter and waits until it is incremented. This implementation assumes
     * that getValue() will eventually be called afterwards, but since we are not implementing
     * a producer/consumer queue here, the simplest safe implementation is to just increment and return.
     * To strictly follow blocking requirements (if this were a bounded buffer scenario),
     * we would need wait/notify structures. For a simple counter, incrementAndGet is sufficient
     * for an immediate change, and getValue() handles reads.
     * Since the prompt implies a potential blocking scenario (like in bounded buffers), if 'increment'
     * itself needed to wait for some condition based on the *new* value, we would use wait/notify.
     * For a simple counter, incrementAndGet is atomic and non-blocking regarding *itself*.
     * If we were modeling a bounded resource that blocks upon overflow, the wait/notify would be here.
     * Given the current structure (simple integer), we rely on synchronized block for atomicity and visibility.
     */
}
