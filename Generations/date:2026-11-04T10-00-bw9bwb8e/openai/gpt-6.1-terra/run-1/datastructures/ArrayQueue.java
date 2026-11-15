/**
 * A bounded, thread-safe FIFO queue. Null elements are not permitted.
 *
 * offer/enqueue and poll/dequeue do not block: they report a full or empty
 * queue using false or null, respectively. put and take block until they can
 * complete, unless interrupted.
 */
@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int size;

    public ArrayQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        elements = new Object[capacity];
    }

    public boolean offer(E element) {
        requireElement(element);
        synchronized (lock) {
            if (size == elements.length) {
                return false;
            }
            insert(element);
            return true;
        }
    }

    public boolean enqueue(E element) {
        return offer(element);
    }

    public void put(E element) throws InterruptedException {
        requireElement(element);
        synchronized (lock) {
            while (size == elements.length) {
                lock.wait();
            }
            insert(element);
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeHead();
        }
    }

    public E dequeue() {
        return poll();
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeHead();
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

    public int capacity() {
        synchronized (lock) {
            return elements.length;
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
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
        lock.notifyAll();
    }

    // Called only while holding lock and when the queue is not empty.
    @SuppressWarnings("unchecked")
    private E removeHead() {
        E element = (E) elements[head];
        elements[head] = null;
        head++;
        if (head == elements.length) {
            head = 0;
        }
        size--;
        lock.notifyAll();
        return element;
    }
}
