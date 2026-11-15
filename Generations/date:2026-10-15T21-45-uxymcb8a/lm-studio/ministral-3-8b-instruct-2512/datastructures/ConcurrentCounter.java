@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
    @interface ThreadSafe {}

    private volatile int count;
    private final Object lock = new Object();

    public ConcurrentCounter() {
        this.count = 0;
    }

    public int getAndIncrement() {
        synchronized (lock) {
            return count++;
        }
    }

    public int getAndDecrement() {
        synchronized (lock) {
            return count--;
        }
    }

    public int getAndSet(int newValue) {
        synchronized (lock) {
            int oldValue = count;
            count = newValue;
            return oldValue;
        }
    }

    public int incrementAndGet() {
        synchronized (lock) {
            return ++count;
        }
    }

    public int decrementAndGet() {
        synchronized (lock) {
            return --count;
        }
    }

    public int get() {
        synchronized (lock) {
            return count;
        }
    }

    public void reset() {
        synchronized (lock) {
            count = 0;
        }
    }

    public int addAndGet(int delta) {
        synchronized (lock) {
            return count += delta;
        }
    }

    public boolean compareAndSet(int expect, int update) {
        synchronized (lock) {
            if (count == expect) {
                count = update;
                return true;
            }
            return false;
        }
    }

    public int getAndAdd(int delta) {
        synchronized (lock) {
            return count += delta;
        }
    }

    public int getAndSetToZero() {
        synchronized (lock) {
            int oldValue = count;
            count = 0;
            return oldValue;
        }
    }

    public int getAndSetTo(int newValue) {
        synchronized (lock) {
            return count = newValue;
        }
    }

    public int getAndIncrementIfPositive() {
        synchronized (lock) {
            if (count > 0) {
                return count++;
            }
            return count;
        }
    }

    public int getAndDecrementIfNegative() {
        synchronized (lock) {
            if (count < 0) {
                return count--;
            }
            return count;
        }
    }

    public int getAndAddIfPositive(int delta) {
        synchronized (lock) {
            if (count > 0 && delta > 0) {
                return count += delta;
            }
            return count;
        }
    }

    public int getAndAddIfNegative(int delta) {
        synchronized (lock) {
            if (count < 0 && delta < 0) {
                return count += delta;
            }
            return count;
        }
    }

    public int getAndSetIfPositive(int newValue) {
        synchronized (lock) {
            if (count > 0 && newValue >= 0) {
                return count = newValue;
            }
            return count;
        }
    }

    public int getAndSetIfNegative(int newValue) {
        synchronized (lock) {
            if (count < 0 && newValue <= 0) {
                return count = newValue;
            }
            return count;
        }
    }

    public int getAndSetIfZero(int newValue) {
        synchronized (lock) {
            if (count == 0 && newValue != 0) {
                return count = newValue;
            }
            return count;
        }
    }

    public int getAndSetIfNonZero(int newValue) {
        synchronized (lock) {
            if (count != 0 && newValue == 0) {
                return count = newValue;
            }
            return count;
        }
    }

}
