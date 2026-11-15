@ConcurrentCounter.ThreadSafe
public class ConcurrentCounter {
    @interface ThreadSafe {}

    private int count = 0;

    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}
