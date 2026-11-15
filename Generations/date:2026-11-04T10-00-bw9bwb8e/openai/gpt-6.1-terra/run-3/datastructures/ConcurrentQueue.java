/**
 * A bounded, thread-safe FIFO queue.
 *
 * Null elements are not permitted. put and take block when the queue is full
 * or empty, respectively. offer and poll return immediately instead.
 */
@ConcurrentQueue.ThreadSafe
public final class ConcurrentQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ConcurrentQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public void put(E element) throws InterruptedException {
        checkElement(element);
        synchronized (lock) {
            while (count == elements.length) {
                lock.wait();
            }
            insert(element);
            lock.notifyAll();
        }
    }

    public boolean offer(E element) {
        checkElement(element);
        synchronized (lock) {
            if (count == elements.length) {
                return false;
            }
            insert(element);
            lock.notifyAll();
            return true;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (count == 0) {
                lock.wait();
            }
            E element = remove();
            lock.notifyAll();
            return element;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (count == 0) {
                return null;
            }
            E element = remove();
            lock.notifyAll();
            return element;
        }
    }

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

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public int capacity() {
        return elements.length;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return elements.length - count;
        }
    }

    private void checkElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Called only while holding lock.
    private void insert(E element) {
        elements[tail] = element;
        tail++;
        if (tail == elements.length) {
            tail = 0;
        }
        count++;
    }

    // Called only while holding lock, when the queue is nonempty.
    @SuppressWarnings("unchecked")
    private E remove() {
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
