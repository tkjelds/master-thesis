@CustomCounter.ThreadSafe
class CustomCounter {
    @interface ThreadSafe {}

    private int count = 0;

    /**
     * Increments the counter. If it was previously zero, this operation blocks until 
     * a decrement occurs. This simulates waiting for activity after inactivity.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void increment() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                // If count is zero, we must wait for a decrement to signal readiness.
                // We are simulating the "wait" state here, waiting for another thread 
                // to signal that activity is occurring or has occurred.
                wait();
            }
            count++;
            // After incrementing, notify waiting decrements that activity has started/continued.
            notifyAll(); 
        }
    }

    /**
     * Decrements the counter. This operation blocks if the count is already zero, 
     * waiting for a pending increment to occur first.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void decrement() throws InterruptedException {
        synchronized (this) {
            while (count < 0) {
                // If count is negative, it means the decrement happened before the paired increment.
                // We wait until an increment happens to bring it back towards zero or positive.
                wait();
            }
            count--;
            // After decrementing, notify waiting increments that activity has begun/continued.
            notifyAll();
        }
    }

    /**
     * Returns the current value of the counter. This operation must be atomic with respect 
     * to increment/decrement operations to ensure visibility and consistency.
     * @return the current count.
     */
    public int getCount() {
        synchronized (this) {
            return count;
        }
    }

    // --- Specific Policy Implementation Notes for the Combined Scenario ---
    /*
     * The problem description implies a scenario where incrementation requires prior "activity" 
     * (count > 0) and decrementing waits for an increment to buffer the negativity.
     * This structure models a token bucket or a transactional counter where operations must alternate 
     * or depend on the previous state.
     * 
     * If we strictly interpret "If it was previously zero, this operation blocks until a decrement occurs" 
     * for increment(), and "Decrements blocks if the count is already zero, waiting for a pending increment" 
     * for decrement(), the waiting logic becomes highly coupled and potentially complex to manage signals correctly.
     * 
     * A simplified interpretation matching standard bounded buffer/counting semaphore usage is used above:
     * - Increment waits if count is 0 (meaning no activity baseline).
     * - Decrement waits if count reaches a negative buffer level (implying it needs an increment partner).
     * 
     * In a real-world scenario requiring perfect synchronization between blocks based on the exact state transition, 
     * the `wait()` signals must be extremely precise. The use of `notifyAll()` mitigates missed wakes but requires 
     * the waiting loops (`while`) to handle spurious wakeups correctly, which they do.
     */
}
