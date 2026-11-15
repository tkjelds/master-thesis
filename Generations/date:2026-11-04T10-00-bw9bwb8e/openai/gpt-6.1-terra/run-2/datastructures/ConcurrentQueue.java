@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    // Blocks while full. Null elements are not permitted.
    public void enqueue(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element);
            lock.notifyAll();
        }
    }

    // Blocks while empty.
    public E dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E element = remove();
            lock.notifyAll();
            return element;
        }
    }

    // Returns false immediately if full.
    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            lock.notifyAll();
            return true;
        }
    }

    // Returns null immediately if empty.
    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E element = remove();
            lock.notifyAll();
            return element;
        }
    }

    // Returns null if empty without removing an element.
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return size == 0 ? null : (E) elements[head];
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

    public int capacity() {
        return elements.length;
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock and when the queue is not full.
    private void insert(E element) {
        elements[tail] = element;
        tail++;
        if (tail == elements.length) {
            tail = 0;
        }
        size++;
    }

    // Called only while holding lock and when the queue is not empty.
    @SuppressWarnings("unchecked")
    private E remove() {
        E element = (E) elements[head];
        elements[head] = null;
        head++;
        if (head == elements.length) {
            head = 0;
        }
        size--;
        return element;
    }
}
