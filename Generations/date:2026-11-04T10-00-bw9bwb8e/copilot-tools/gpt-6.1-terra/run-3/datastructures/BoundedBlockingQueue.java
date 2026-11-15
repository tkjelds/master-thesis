/**
 * A bounded FIFO queue that does not permit null elements.
 *
 * put and take block until they can complete or the calling thread is
 * interrupted. offer and poll return immediately when the queue is full
 * or empty, respectively.
 */
@BoundedBlockingQueue.ThreadSafe
public final class BoundedBlockingQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public BoundedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
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

    public E peek() {
        synchronized (lock) {
            return count == 0 ? null : elementAtHead();
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
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
        count++;
    }

    private E dequeue() {
        E element = elementAtHead();
        elements[head] = null;
        head = (head + 1) % elements.length;
        count--;
        return element;
    }

    @SuppressWarnings("unchecked")
    private E elementAtHead() {
        return (E) elements[head];
    }
}
