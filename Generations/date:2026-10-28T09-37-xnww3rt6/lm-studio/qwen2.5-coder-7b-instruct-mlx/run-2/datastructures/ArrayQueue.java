@ArrayQueue.ThreadSafe
public class ArrayQueue {
    @interface ThreadSafe {}

    private Object[] elements;
    private int head = 0;
    private int tail = 0;

    public ArrayQueue(int capacity) {
        elements = new Object[capacity];
    }

    public synchronized void enqueue(Object item) throws InterruptedException {
        while (isFull()) {
            wait();
        }
        elements[tail] = item;
        tail = (tail + 1) % elements.length;
        notifyAll();
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Object item = elements[head];
        head = (head + 1) % elements.length;
        notifyAll();
        return item;
    }

    public synchronized boolean isEmpty() {
        return head == tail;
    }

    private synchronized boolean isFull() {
        return (tail + 1) % elements.length == head;
    }
}
