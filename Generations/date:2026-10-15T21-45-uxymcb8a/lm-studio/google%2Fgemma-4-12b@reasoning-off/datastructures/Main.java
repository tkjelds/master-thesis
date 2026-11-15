@ConcurrentCounter.ThreadSafe
class ConcurrentCounter {
    @interface ThreadSafe {}

    private long count = 0;

    /**
     * Increments the counter by a given amount.
     * @param increment The value to add.
     */
    public void increment(long increment) {
        synchronized (this) {
            this.count += increment;
        }
    }

    /**
     * Decrements the counter by a given amount.
     * @param decrement The value to subtract.
     */
    public void decrement(long decrement) {
        synchronized (this) {
            this.count -= decrement;
        }
    }

    /**
     * Gets the current value of the counter.
     * @return The current count.
     */
    public long getCount() {
        synchronized (this) {
            return this.count;
        }
    }

    /**
     * Resets the counter to zero.
     */
    public void reset() {
        synchronized (this) {
            this.count = 0;
        }
    }

    /**
     * Example of a blocking operation that waits until the counter 
     * reaches a minimum value.
     * @param threshold The minimum value to wait for.
     * @return The count once condition is met.
     */
    public long waitForMin(long threshold) throws InterruptedException {
        synchronized (this) {
            while (this.count < threshold) {
                this.wait();
            }
            return this.count;
        }
    }

    /**
     * Increments the count and notifies any threads waiting 
     * for a minimum value.
     * @param increment The value to add.
     */
    public void incrementAndNotify(long increment) {
        synchronized (this) {
            this.count += increment;
            if (this.count >= 0) { // Logic check for notification
                this.notifyAll();
            }
        }
    }
}

@Main.ThreadSafe
public class Main {
    @interface ThreadSafe {}

    public static void main(String[] args) {
        // Example usage implementation would go here.
    }
}
