@b_tres.ThreadSafe
public class b_tres {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object[] items;
    private int head;
    private int tail;
    private int size;

    public b_tres(int capacity) {
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public synchronized void enqueue(Object item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        items[tail] = item;
        tail = (tail + 1) % capacity;
        size++;
        notifyAll();
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Object item = items[head];
        head = (head + 1) % capacity;
        size--;
        notifyAll();
        return item;
    }

    public synchronized boolean isFull() {
        return size == capacity;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
