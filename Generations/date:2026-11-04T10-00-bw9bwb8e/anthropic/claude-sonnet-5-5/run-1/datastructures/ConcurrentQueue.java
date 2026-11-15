@ConcurrentQueue.ThreadSafe
public class ConcurrentQueue<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;
    private final int capacity;
    private boolean closed;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
        this.closed = false;
    }

    public ConcurrentQueue() {
        this(1024);
    }

    // Blocks while the queue is full. Throws IllegalStateException if closed.
    public void enqueue(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("null items not permitted");
        }
        synchronized (lock) {
            while (count == capacity && !closed) {
                lock.wait();
            }
            if (closed) {
                throw new IllegalStateException("queue closed");
            }
            items[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            lock.notifyAll();
        }
    }

    // Blocks while the queue is empty. Returns null if closed and drained.
    @SuppressWarnings("unchecked")
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0 && !closed) {
                lock.wait();
            }
            if (count == 0) {
                return null;
            }
            T item = (T) items[head];
            items[head] = null;
            head = (head + 1) % capacity;
            count--;
            lock.notifyAll();
            return item;
        }
    }

    // Non-blocking: returns false if full or closed.
    public boolean offer(T item) {
        if (item == null) {
            throw new NullPointerException("null items not permitted");
        }
        synchronized (lock) {
            if (closed || count == capacity) {
                return false;
            }
            items[tail] = item;
            tail = (tail + 1) % capacity;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking: returns null if empty.
    @SuppressWarnings("unchecked")
    public T poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            T item = (T) items[head];
            items[head] = null;
            head = (head + 1) % capacity;
            count--;
            lock.notifyAll();
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    public T peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (T) items[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }
}
