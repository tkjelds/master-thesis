@Counter.ThreadSafe
public final class Counter {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private long value;

    public void increment() {
        synchronized (lock) {
            value++;
        }
    }

    public void decrement() {
        synchronized (lock) {
            value--;
        }
    }

    public void add(long amount) {
        synchronized (lock) {
            value += amount;
        }
    }

    public long getValue() {
        synchronized (lock) {
            return value;
        }
    }

    public void reset() {
        synchronized (lock) {
            value = 0;
        }
    }
}
