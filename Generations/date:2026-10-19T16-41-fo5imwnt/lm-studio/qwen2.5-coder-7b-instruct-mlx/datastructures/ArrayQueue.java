@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final int[] array;
    private int head = 0;
    private int tail = 0;
    private final int capacity;

    public ArrayQueue(int capacity) {
        this.capacity = capacity;
        array = new int[capacity];
    }

    public synchronized void enqueue(int item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        array[tail] = item;
        tail = (tail + 1) % capacity;
        notifyAll();
    }

    public synchronized int dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        int item = array[head];
        head = (head + 1) % capacity;
        notifyAll();
        return item;
    }

    public synchronized boolean isEmpty() {
        return head == tail;
    }

    public synchronized boolean isFull() {
        return (tail + 1) % capacity == head;
    }

    public synchronized int size() {
        return (tail - head + capacity) % capacity;
    }
}
