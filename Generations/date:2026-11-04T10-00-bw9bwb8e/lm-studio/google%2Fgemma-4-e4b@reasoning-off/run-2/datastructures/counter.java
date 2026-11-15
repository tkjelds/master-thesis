@MyCounter.ThreadSafe
class MyCounter {
    @interface ThreadSafe {}


    private int count = 0;

    /**
     * Increments the counter. If the counter is currently at its maximum capacity,
     * this operation blocks until space becomes available.
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public void increment() throws InterruptedException {
        synchronized (this) {
            while (this.count >= 10) { // Assuming a capacity constraint of 10 for demonstration of blocking
                this.wait();
            }
            count++;
        }
    }

    /**
     * Decrements the counter. If the counter is currently at zero, this operation blocks 
     * until a count is available (i.e., another thread has decremented it, assuming 
     * the counter represents availability). For a simple bounded buffer scenario (which this resembles), 
     * decrementing when count is zero means waiting for an incrementer to finish its transaction. 
     * For a simple counter implementation without capacity constraints, this decrement might be simpler.
     * Assuming here that decrement only succeeds if count > 0, otherwise it waits for a positive count to become available.
     * For a simple counter that can go negative, the waiting condition might change. 
     * Sticking to a simple runnable counter model where decrement blocks if it goes below zero conceptually:
     */
    public void decrement() throws InterruptedException {
        synchronized (this) {
            while (this.count <= 0) { // Waiting if we are already at or below the conceptual minimum (0)
                this.wait();
            }
            count--;
        }
    }

    /**
     * Returns the current value of the counter. This operation is thread-safe.
     * @return The current count.
     */
    public int getValue() {
        synchronized (this) {
            return this.count;
        }
    }

    /**
     * Signals that the counter has been incremented, potentially allowing a waiting decrementer to proceed.
     * This should be called after successfully incrementing if blocking occurred on decrement.
     */
    public void signalIncrement() {
        synchronized (this) {
            // If we just increased the count, and someone might be waiting on decrement (count <= 0), wake them up.
            this.notifyAll();
        }
    }

    /**
     * Signals that the counter has been decremented, potentially allowing a waiting incrementer to proceed.
     * This should be called after successfully decrementing if blocking occurred on increment.
     */
    public void signalDecrement() {
        synchronized (this) {
            // If we just decreased the count, and someone might be waiting on increment (count >= 10), wake them up.
            this.notifyAll();
        }
    }
}
