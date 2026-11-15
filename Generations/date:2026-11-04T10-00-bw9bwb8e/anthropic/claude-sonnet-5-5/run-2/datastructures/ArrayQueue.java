@ArrayQueue.ThreadSafe
public class ArrayQueue<E> {
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
    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            while (count == items.length) {
                lock.wait();
            }
            items[tail] = element;
            tail = (tail + 1) % items.length;
            count++;
            lock.notifyAll();
        }
    }

    // Blocks while the queue is empty.
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking insert; returns false if full.
    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("null elements not permitted");
        }
        synchronized (lock) {
            if (count == items.length) {
                return false;
            }
            items[tail] = element;
            tail = (tail + 1) % items.length;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    // Non-blocking remove; returns null if empty.
    @SuppressWarnings("unchecked")
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E e = (E) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            lock.notifyAll();
            return e;
        }
    }

    // Non-blocking peek; returns null if empty.
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            return (E) items[head];
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

    public boolean isFull() {
        synchronized (lock) {
            return count == items.length;
        }
    }

    public int capacity() {
        return items.length;
    }
}
