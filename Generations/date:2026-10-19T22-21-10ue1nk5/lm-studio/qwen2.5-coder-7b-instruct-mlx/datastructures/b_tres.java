@b_tres.ThreadSafe
public class b_tres {
    @interface ThreadSafe {}

    private int[] buffer;
    private int head = 0;
    private int tail = 0;
    private final int capacity;

    public b_tres(int capacity) {
        this.capacity = capacity;
        buffer = new int[capacity];
    }

    public synchronized boolean enq(int value) {
        while ((tail + 1) % capacity == head) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        buffer[tail] = value;
        tail = (tail + 1) % capacity;
        notifyAll();
        return true;
    }

    public synchronized int deq() {
        while (head == tail) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Thread was interrupted", e);
            }
        }
        int value = buffer[head];
        head = (head + 1) % capacity;
        notifyAll();
        return value;
    }

    public synchronized boolean full() {
        return (tail + 1) % capacity == head;
    }

    public synchronized boolean empty() {
        return head == tail;
    }
}
