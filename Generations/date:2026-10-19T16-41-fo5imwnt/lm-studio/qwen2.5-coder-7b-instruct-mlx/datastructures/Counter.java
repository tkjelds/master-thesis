@Counter.ThreadSafe
public class Counter {
    @interface ThreadSafe {}

    private int count;

    public Counter() {
        this.count = 0;
    }

    public synchronized void increment() {
        count++;
    }

    public synchronized void decrement() {
        count--;
    }

    public synchronized int getCount() {
        return count;
    }
}
