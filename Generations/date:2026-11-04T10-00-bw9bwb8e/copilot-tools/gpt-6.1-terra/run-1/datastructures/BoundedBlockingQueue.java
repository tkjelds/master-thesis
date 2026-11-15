/**
 * A bounded, thread-safe FIFO queue.
 *
 * Null elements are not permitted. put and take wait when the queue is full
 * or empty, respectively; offer and poll return immediately instead.
 */
@BoundedBlockingQueue.ThreadSafe
public final class BoundedBlockingQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
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

    public E peek() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return elementAt(head);
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

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    private void enqueue(E element) {
        elements[tail] = element;
        tail = (tail + 1) % elements.length;
        size++;
    }

    private E dequeue() {
        E element = elementAt(head);
        elements[head] = null;
        head = (head + 1) % elements.length;
        size--;
        return element;
    }

    @SuppressWarnings("unchecked")
    private E elementAt(int index) {
        return (E) elements[index];
    }
}
