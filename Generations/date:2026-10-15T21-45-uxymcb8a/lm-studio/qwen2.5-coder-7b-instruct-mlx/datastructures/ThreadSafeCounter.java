@ThreadSafeCounter.ThreadSafe
public class ThreadSafeCounter {
    @interface ThreadSafe {}

    private volatile int count = 0;

    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}
