@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private final int[] elements;
    private int head = 0;
    private int tail = 0;

    public ArrayQueue(int capacity) {
        elements = new int[capacity];
    }

    public synchronized boolean enqueue(int element) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        notifyAll();
        return true;
    }

    public synchronized int dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        int element = elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return element;
    }

    private synchronized boolean isFull() {
        return (tail + 1) % elements.length == head;
    }

    private synchronized boolean isEmpty() {
        return head == tail;
    }
}
