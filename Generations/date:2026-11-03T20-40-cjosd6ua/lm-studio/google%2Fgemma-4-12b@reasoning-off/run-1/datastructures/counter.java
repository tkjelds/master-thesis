class ConcurrentCounter {
    private int count;

    public ConcurrentCounter() {
        this.count = 0;
    }

    /**
     * Increments the counter and returns the new value.
     */
    public int increment() {
        synchronized (this) {
            count++;
            return count;
        }
    }

    /**
     * Decrements the counter and returns the new value.
     */
    public int decrement() {
        synchronized (this) {
            count--;
            return count;
        }
    }

    /**
     * Returns the current value of the counter.
     */
    public int get() {
        synchronized (this) {
            return count;
        }
    }

    /**
     * Resets the counter to 0.
     */
    public void reset() {
        synchronized (this) {
            count = 0;
        }
    }

    /**
     * Helper method to demonstrate functionality in a multi-threaded environment.
     */
    public static void main(String[] args) {
        ConcurrentCounter counter = new ConcurrentCounter();
        int iterations = 1000;
        Thread[] threads = new Thread[2];

        for (int i = 0; i < 2; i++) {
            final ConcurrentCounter sharedCounter = counter;
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    for (int j = 0; j < iterations; j++) {
                        sharedCounter.increment();
                    }
                }
            });
        }

        for (int i = 0; i < 2; i++) {
            threads[i].start();
        }

        try {
            for (int i = 0; i < 2; i++) {
                threads[i].join();
            }
        } catch (InterruptedException e) {
            // Handle interrupt
        }

        System.out.println("Final count: " + counter.get());
    }
}
