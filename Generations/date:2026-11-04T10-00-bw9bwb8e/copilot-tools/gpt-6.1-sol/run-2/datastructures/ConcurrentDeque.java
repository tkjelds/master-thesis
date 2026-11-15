/**
 * A bounded, thread-safe deque.
 *
 * Null elements are not permitted. putFirst/putLast block while full;
 * takeFirst/takeLast block while empty. Blocking operations are interruptible.
 * offerFirst/offerLast return false when full; pollFirst/pollLast and
 * peekFirst/peekLast return null when empty.
 *
 * All state changes are protected by one intrinsic monitor. No fairness
 * guarantee is made between waiting threads.
 */
@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public void putFirst(E value) throws InterruptedException {
        requireValue(value);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkFirst(value);
        }
    }

    public void putLast(E value) throws InterruptedException {
        requireValue(value);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkLast(value);
        }
    }

    public boolean offerFirst(E value) {
        requireValue(value);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkFirst(value);
            return true;
        }
    }

    public boolean offerLast(E value) {
        requireValue(value);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkLast(value);
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return unlinkLast();
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            return size == 0 ? null : unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.value;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (monitor) {
            first = null;
            last = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // Link and unlink helpers are called only while holding monitor.
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
        monitor.notifyAll();
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
        monitor.notifyAll();
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
        monitor.notifyAll();
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
        monitor.notifyAll();
        return node.value;
    }
}
