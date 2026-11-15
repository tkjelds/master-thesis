@ArrayQueue.ThreadSafe
public class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object[] buffer;
    private final Object lock;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.buffer = new Object[capacity];
        this.lock = new Object();
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    public int capacity() {
        synchronized (lock) {
            return buffer.length;
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
            return size == buffer.length;
        }
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            if (size == buffer.length) {
                return false;
            }
            enqueueLocked(value);
            return true;
        }
    }

    public void enqueue(E value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            while (size == buffer.length) {
                lock.wait();
            }
            enqueueLocked(value);
        }
    }

    public void put(E value) throws InterruptedException {
        enqueue(value);
    }

    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            if (size == buffer.length) {
                throw new IllegalStateException("queue is full");
            }
            enqueueLocked(value);
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return dequeueLocked();
        }
    }

    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return dequeueLocked();
        }
    }

    public E take() throws InterruptedException {
        return dequeue();
    }

    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return (E) buffer[head];
        }
    }

    private void enqueueLocked(E value) {
        buffer[tail] = value;
        tail = (tail + 1) % buffer.length;
        size++;
        lock.notifyAll();
    }

    private E dequeueLocked() {
        E value = (E) buffer[head];
        buffer[head] = null;
        head = (head + 1) % buffer.length;
        size--;
        lock.notifyAll();
        return value;
    }
}
