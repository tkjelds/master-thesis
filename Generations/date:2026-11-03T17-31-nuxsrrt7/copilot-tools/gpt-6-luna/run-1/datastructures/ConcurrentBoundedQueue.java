@ConcurrentBoundedQueue.ThreadSafe
public final class ConcurrentBoundedQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final int capacity;
    private int head;
    private int tail;
    private int size;

    public ConcurrentBoundedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.elements = new Object[capacity];
    }

    public void put(E item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            elements[tail] = item;
            tail = (tail + 1) % capacity;
            size++;
            notifyAll();
        }
    }

    public boolean offer(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            elements[tail] = item;
            tail = (tail + 1) % capacity;
            size++;
            notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E item = dequeue();
            notifyAll();
            return item;
        }
    }

    public E poll() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            E item = dequeue();
            notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int remainingCapacity() {
        synchronized (this) {
            return capacity - size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    private E dequeue() {
        @SuppressWarnings("unchecked")
        E item = (E) elements[head];
        elements[head] = null;
        head = (head + 1) % capacity;
        size--;
        return item;
    }
}
