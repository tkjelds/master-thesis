@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void enqueue(T element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Queue elements cannot be null");
        }

        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }

            T element = (T) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }
}
