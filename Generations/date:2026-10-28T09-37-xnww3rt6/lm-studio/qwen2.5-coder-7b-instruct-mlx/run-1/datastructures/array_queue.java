@ArrayQueue.ThreadSafe
class ArrayQueue {
    @interface ThreadSafe {}

    private final int capacity;
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    public void enqueue(Object element) throws InterruptedException {
        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            count++;
            notifyAll();
        }
    }

    public Object dequeue() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Object element = elements[head];
            head = (head + 1) % capacity;
            count--;
            notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }
}
