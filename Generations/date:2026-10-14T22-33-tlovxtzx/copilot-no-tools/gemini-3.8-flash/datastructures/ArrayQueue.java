@ArrayQueue.ThreadSafe
public class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private final int capacity;

    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than zero");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
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

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return element;
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
            elements[tail] = element;
            tail = (tail + 1) % capacity;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % capacity;
            size--;
            lock.notifyAll();
            return element;
        }
    }

    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            E element = (E) elements[head];
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

    public int capacity() {
        return capacity;
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
}
