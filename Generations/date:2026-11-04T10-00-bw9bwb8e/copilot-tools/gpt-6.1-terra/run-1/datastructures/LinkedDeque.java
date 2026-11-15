/**
 * A bounded, thread-safe linked deque that does not permit null elements.
 *
 * Nonblocking offer and poll operations return false or null when they cannot
 * complete. Add and remove operations throw IllegalStateException in that case.
 * Put and take operations wait until they can complete and are interruptible.
 * Waiting threads are not guaranteed to be served in any particular order.
 */
@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    /** Creates a deque with a capacity of 2147483647 elements. */
    public LinkedDeque() {
        this(2147483647);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
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

    public boolean offerFirst(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(item);
            return true;
        }
    }

    public boolean offerLast(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(item);
            return true;
        }
    }

    public void addFirst(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("deque is full");
            }
            linkFirst(item);
        }
    }

    public void addLast(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("deque is full");
            }
            linkLast(item);
        }
    }

    public void putFirst(E item) throws InterruptedException {
        requireItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(item);
        }
    }

    public void putLast(E item) throws InterruptedException {
        requireItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(item);
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

    public E removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("deque is empty");
            }
            return unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    public E getFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("deque is empty");
            }
            return first.item;
        }
    }

    public E getLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("deque is empty");
            }
            return last.item;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                first = null;
                last = null;
                size = 0;
                lock.notifyAll();
            }
        }
    }

    private void requireItem(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private void linkFirst(E item) {
        Node<E> node = new Node<E>(item);
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

    private void linkLast(E item) {
        Node<E> node = new Node<E>(item);
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
        return node.item;
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
        return node.item;
    }
}
