/**
 * A bounded, thread-safe FIFO queue.
 *
 * put and take wait for space or an element, respectively, and are interruptible.
 * offer and poll return immediately; offer returns false when full, and poll
 * returns null when empty. Null elements are not permitted.
 */
@ArrayQueue.ThreadSafe
public final class ArrayQueue<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Object[] elements;
    private int head;
    private int tail;
    private int count;

    public ArrayQueue(int capacity) {
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
            return count == 0 ? null : firstElement();
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

    public void clear() {
        synchronized (lock) {
            if (count == 0) {
                return;
            }
            while (count > 0) {
                dequeue();
            }
            head = 0;
            tail = 0;
            lock.notifyAll();
        }
    }

    private void requireElement(E element) {
        if (element == null) {
            throw new NullPointerException("element must not be null");
        }
    }

    private void enqueue(E element) {
        elements[tail] = element;
        tail++;
        if (tail == elements.length) {
            tail = 0;
        }
        count++;
    }

    private E dequeue() {
        E element = firstElement();
        elements[head] = null;
        head++;
        if (head == elements.length) {
            head = 0;
        }
        count--;
        return element;
    }

    @SuppressWarnings("unchecked")
    private E firstElement() {
        return (E) elements[head];
    }
}
