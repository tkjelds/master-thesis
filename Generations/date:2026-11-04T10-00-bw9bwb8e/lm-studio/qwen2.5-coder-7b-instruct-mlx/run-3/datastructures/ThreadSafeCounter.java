@ThreadSafeCounter.ThreadSafe
public class ThreadSafeCounter {
    @interface ThreadSafe {}

    private int count;

    public ThreadSafeCounter() {
        this.count = 0;
    }

    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}
