@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final long min;
    private final long max;
    private final long initialValue;
    private long value;

    public ConcurrentCounter() {
        this(0L, 0L, Long.MAX_VALUE);
    }

    public ConcurrentCounter(long max) {
        this(0L, 0L, max);
    }

    public ConcurrentCounter(long min, long max) {
        this(min, min, max);
    }

    public ConcurrentCounter(long initialValue, long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("Minimum bound cannot exceed maximum bound.");
        }
        if (initialValue < min || initialValue > max) {
            throw new IllegalArgumentException("Initial value must be within [min, max] bounds.");
        }
        this.min = min;
        this.max = max;
        this.initialValue = initialValue;
        this.value = initialValue;
    }

    public void increment() throws InterruptedException {
        synchronized (lock) {
            while (value >= max) {
                lock.wait();
            }
            value++;
            lock.notifyAll();
        }
    }

    public void decrement() throws InterruptedException {
        synchronized (lock) {
            while (value <= min) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
        }
    }

    public long incrementAndGet() throws InterruptedException {
        synchronized (lock) {
            while (value >= max) {
                lock.wait();
            }
            value++;
            lock.notifyAll();
            return value;
        }
    }

    public long decrementAndGet() throws InterruptedException {
        synchronized (lock) {
            while (value <= min) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
            return value;
        }
    }

    public long getAndIncrement() throws InterruptedException {
        synchronized (lock) {
            while (value >= max) {
                lock.wait();
            }
            long previous = value;
            value++;
            lock.notifyAll();
            return previous;
        }
    }

    public long getAndDecrement() throws InterruptedException {
        synchronized (lock) {
            while (value <= min) {
                lock.wait();
            }
            long previous = value;
            value--;
            lock.notifyAll();
            return previous;
        }
    }

    public boolean tryIncrement() {
        synchronized (lock) {
            if (value >= max) {
                return false;
            }
            value++;
            lock.notifyAll();
            return true;
        }
    }

    public boolean tryDecrement() {
        synchronized (lock) {
            if (value <= min) {
                return false;
            }
            value--;
            lock.notifyAll();
            return true;
        }
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = initialValue;
            lock.notifyAll();
        }
    }

    public void awaitValue(long targetValue) throws InterruptedException {
        if (targetValue < min || targetValue > max) {
            throw new IllegalArgumentException("Target value is outside allowable bounds.");
        }
        synchronized (lock) {
            while (value != targetValue) {
                lock.wait();
            }
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return value >= max;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return value <= min;
        }
    }

    public long getMin() {
        return min;
    }

    public long getMax() {
        return max;
    }
}
