@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int count;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void enqueue(E element) throws InterruptedException {
        synchronized (lock) {
            while (count == capacity) {
                lock.wait();
            }

            elements[tail] = element;
            tail = (tail + 1) % capacity;
            count++;
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }

            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            count--;
            lock.notifyAll();
            return element;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == capacity;
        }
    }
}
