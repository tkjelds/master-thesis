@ThreadSafeCounter.ThreadSafe
public class ThreadSafeCounter {
    @interface ThreadSafe {}

    private volatile int count;

    public ThreadSafeCounter() {
        this.count = 0;
    }

    public void increment() {
        synchronized (this) {
            count++;
        }
    }

    public int getCount() {
        synchronized (this) {
            return count;
        }
    }
}
