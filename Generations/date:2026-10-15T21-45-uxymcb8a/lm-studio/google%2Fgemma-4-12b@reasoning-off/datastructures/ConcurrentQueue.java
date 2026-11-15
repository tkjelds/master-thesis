@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private T[] elements;
    private int head = 0;
    private int tail = 0;
    private int size = 0;
    private final int capacity;

    @SuppressWarnings("unchecked")
    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.elements = (T[]) new Object[capacity];
    }

    public void enqueue(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot enqueue null");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            elements[tail] = item;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T item = elements[head];
            elements[head] = null; // Clear reference for GC
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }
}
