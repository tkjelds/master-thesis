@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;
    private final Object lock;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero: " + capacity);
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
        this.lock = new Object();
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) elements[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < capacity; i++) {
                elements[i] = null;
            }
            head = 0;
            tail = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    private void enqueue(E element) {
        elements[tail] = element;
        tail = (tail + 1) % capacity;
        size++;
    }

    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return element;
    }
}
