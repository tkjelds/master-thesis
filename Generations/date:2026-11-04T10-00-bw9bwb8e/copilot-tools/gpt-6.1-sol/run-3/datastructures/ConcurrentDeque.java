/**
 * A thread-safe, bounded, double-ended queue.
 *
 * Null elements are not permitted. Offers return false when full; polls and
 * peeks return null when empty. Puts and takes block interruptibly until they
 * can complete. Waiting threads are not guaranteed fair ordering.
 *
 * The no-argument constructor uses the largest supported capacity.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public ConcurrentDeque() {
        this(2147483647);
    }

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean offerFirst(E value) {
        requireNonNull(value);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(value);
            return true;
        }
    }

    public boolean offerLast(E value) {
        requireNonNull(value);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(value);
            return true;
        }
    }

    /**
     * If interrupted before insertion, throws without inserting the element.
     */
    public void putFirst(E value) throws InterruptedException {
        requireNonNull(value);
        synchronized (lock) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(value);
        }
    }

    /**
     * If interrupted before insertion, throws without inserting the element.
     */
    public void putLast(E value) throws InterruptedException {
        requireNonNull(value);
        synchronized (lock) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            while (size == capacity) {
                lock.wait();
            }
            linkLast(value);
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return size == 0 ? null : unlinkLast();
        }
    }

    /**
     * If interrupted before removal, throws without removing an element.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    /**
     * If interrupted before removal, throws without removing an element.
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
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
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            size = 0;
            lock.notifyAll();
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Link and unlink helpers are called only while holding lock.
    private void linkFirst(E value) {
        Node<E> node = new Node<E>(value);
        node.next = first;
        if (first == null) {
            last = node;
        } else {
            first.previous = node;
        }
        first = node;
        size++;
        lock.notifyAll();
    }

    private void linkLast(E value) {
        Node<E> node = new Node<E>(value);
        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
        size++;
        lock.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> node = first;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        node.next = null;
        size--;
        lock.notifyAll();
        return node.value;
    }

    private E unlinkLast() {
        Node<E> node = last;
        last = node.previous;
        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }
        node.previous = null;
        size--;
        lock.notifyAll();
        return node.value;
    }
}
