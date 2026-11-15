@BoundedTres.ThreadSafe
public class BoundedTres {
    @interface ThreadSafe {}

    private static final int MAX_SIZE = 3;
    private volatile int[] elements = new int[MAX_SIZE];
    private volatile int count = 0;
    private volatile int head = 0;
    private volatile int tail = 0;

    public BoundedTres() {
        // Initialize with empty structure
    }

    public synchronized void put(int value) throws InterruptedException {
        while (count == MAX_SIZE) {
            wait();
        }
        elements[tail] = value;
        tail = (tail + 1) % MAX_SIZE;
        count++;
        notifyAll();
    }

    public synchronized int get() throws InterruptedException {
        while (count == 0) {
            wait();
        }
        int value = elements[head];
        head = (head + 1) % MAX_SIZE;
        count--;
        notifyAll();
        return value;
    }

    public synchronized boolean isEmpty() {
        return count == 0;
    }

    public synchronized boolean isFull() {
        return count == MAX_SIZE;
    }
}
