/**
 * A bounded, thread-safe FIFO collection.
 *
 * Null elements are not permitted. put and take wait when the collection is
 * full or empty, respectively; offer and poll return immediately instead.
 * Waiting operations propagate InterruptedException without changing the
 * collection. No fairness or ordering among waiting threads is guaranteed.
 */
@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (count == elements.length) {
                lock.wait();
            }
            enqueue(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (count == elements.length) {
                return false;
            }
            enqueue(element);
            lock.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns and removes the oldest element, or null if empty.
     */
    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = dequeue();
            lock.notifyAll();
            return element;
        }
    }

    /**
     * Returns the oldest element without removing it, or null if empty.
     */
    @SuppressWarnings("unchecked")
    public E peek() {
        synchronized (lock) {
            return count == 0 ? null : (E) elements[head];
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return elements.length;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return count == elements.length;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (count == 0) {
                return;
            }
            int index = head;
            for (int i = 0; i < count; i++) {
                elements[index] = null;
                index++;
                if (index == elements.length) {
                    index = 0;
                }
            }
            head = 0;
            tail = 0;
            count = 0;
            lock.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element");
        }
    }

    // Called only while holding lock and when space is available.
    private void enqueue(E element) {
        elements[tail] = element;
        tail++;
        if (tail == elements.length) {
            tail = 0;
        }
        count++;
    }

    // Called only while holding lock and when an element is available.
    @SuppressWarnings("unchecked")
    private E dequeue() {
        E element = (E) elements[head];
        elements[head] = null;
        head++;
        if (head == elements.length) {
            head = 0;
        }
        count--;
        return element;
    }
}
