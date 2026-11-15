@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private long value;

    public ConcurrentCounter() {
        this(0L);
    }

    public ConcurrentCounter(long initialValue) {
        this.value = initialValue;
    }

    public long increment() {
        synchronized (lock) {
            value++;
            lock.notifyAll();
            return value;
        }
    }

    public long decrement() {
        synchronized (lock) {
            value--;
            lock.notifyAll();
            return value;
        }
    }

    public long add(long delta) {
        synchronized (lock) {
            value += delta;
            lock.notifyAll();
            return value;
        }
    }

    public long get() {
        synchronized (lock) {
            return value;
        }
    }

    public void set(long newValue) {
        synchronized (lock) {
            value = newValue;
            lock.notifyAll();
        }
    }

    public long getAndIncrement() {
        synchronized (lock) {
            long old = value;
            value++;
            lock.notifyAll();
            return old;
        }
    }

    public long getAndDecrement() {
        synchronized (lock) {
            long old = value;
            value--;
            lock.notifyAll();
            return old;
        }
    }

    public boolean compareAndSet(long expected, long newValue) {
        synchronized (lock) {
            if (value == expected) {
                value = newValue;
                lock.notifyAll();
                return true;
            }
            return false;
        }
    }

    // Blocks until the counter is at least the given threshold; returns the value observed.
    public long awaitAtLeast(long threshold) throws InterruptedException {
        synchronized (lock) {
            while (value < threshold) {
                lock.wait();
            }
            return value;
        }
    }

    // Blocks until the counter is at most the given threshold; returns the value observed.
    public long awaitAtMost(long threshold) throws InterruptedException {
        synchronized (lock) {
            while (value > threshold) {
                lock.wait();
            }
            return value;
        }
    }

    // Blocks until the counter is positive, then decrements it (semaphore-like acquire).
    public long decrementIfPositive() throws InterruptedException {
        synchronized (lock) {
            while (value <= 0) {
                lock.wait();
            }
            value--;
            lock.notifyAll();
            return value;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "ConcurrentCounter[" + value + "]";
        }
    }

    public static void main(String[] args) throws InterruptedException {
        final ConcurrentCounter counter = new ConcurrentCounter();
        final int threads = 8;
        final int perThread = 10000;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(new Runnable() {
                public void run() {
                    for (int j = 0; j < perThread; j++) {
                        counter.increment();
                    }
                }
            });
            workers[i].start();
        }
        for (int i = 0; i < threads; i++) {
            workers[i].join();
        }
        System.out.println(counter.get());
    }
}
