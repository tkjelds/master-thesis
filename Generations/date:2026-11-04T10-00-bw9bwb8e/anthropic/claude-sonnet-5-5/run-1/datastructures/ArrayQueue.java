@ArrayQueue.ThreadSafe
public class ArrayQueue<T> {
    @interface ThreadSafe {}


    private final Object lock = new Object();
    private final Object[] items;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.items = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.count = 0;
    }

    // Blocks while the queue is full.
    public void enqueue(T item) throws InterruptedException {
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;
            lock.notifyAll();
        }
    }

    // Blocks while the queue is empty.
    @SuppressWarnings("unchecked")
    public T dequeue() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            T item = (T) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return item;
        }
    }

    // Non-blocking: returns false if full.
    public boolean offer(T item) {
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[tail] = item;
            tail = (tail + 1) % items.length;
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
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return item;
        }
    }

    // Non-blocking: returns null if empty.
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

    public int capacity() {
        return items.length;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }
}
