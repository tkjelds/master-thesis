@ArrayQueue.ThreadSafe
public class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object[] elements;
    private final Object lock = new Object();
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    /*
     * The queue is bounded and FIFO. Null elements are not permitted.
     * put and take block; offer, poll, and peek return immediately.
     * poll and peek return null when the queue is empty.
     */

    public void put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element);
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return remove();
        }
    }

    public void enqueue(E element) throws InterruptedException {
        put(element);
    }

    public E dequeue() throws InterruptedException {
        return take();
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            return true;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return remove();
        }
    }

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

    public int capacity() {
        return elements.length;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == elements.length;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size == 0) {
                return;
            }
            int index = head;
            for (int i = 0; i < size; i++) {
                elements[index] = null;
                index = next(index);
            }
            head = 0;
            tail = 0;
            size = 0;
            lock.notifyAll();
        }
    }

    // The caller must hold lock.
    private void insert(E element) {
        elements[tail] = element;
        tail = next(tail);
        size++;
        lock.notifyAll();
    }

    // The caller must hold lock.
    @SuppressWarnings("unchecked")
    private E remove() {
        E element = (E) elements[head];
        elements[head] = null;
        head = next(head);
        size--;
        lock.notifyAll();
        return element;
    }

    private int next(int index) {
        index++;
        return index == elements.length ? 0 : index;
    }
}
