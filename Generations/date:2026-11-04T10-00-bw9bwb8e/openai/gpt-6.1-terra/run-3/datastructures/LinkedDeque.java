@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final int MAX_CAPACITY = 2147483647;

    private static final class Node<T> {
        T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    /**
     * Creates a deque with the largest representable capacity.
     */
    public LinkedDeque() {
        this(MAX_CAPACITY);
    }

    /**
     * Creates a deque with the given positive capacity.
     */
    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    private void checkElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    /* The following link and unlink helpers are called only while holding lock. */

    private void linkFirst(E element) {
        Node<E> node = new Node<E>(element);
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

    private void linkLast(E element) {
        Node<E> node = new Node<E>(element);
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
        Node<E> next = node.next;
        first = next;
        if (next == null) {
            last = null;
        } else {
            next.previous = null;
        }
        size--;
        E element = node.item;
        node.item = null;
        node.next = null;
        lock.notifyAll();
        return element;
    }

    private E unlinkLast() {
        Node<E> node = last;
        Node<E> previous = node.previous;
        last = previous;
        if (previous == null) {
            first = null;
        } else {
            previous.next = null;
        }
        size--;
        E element = node.item;
        node.item = null;
        node.previous = null;
        lock.notifyAll();
        return element;
    }

    /**
     * Nonblocking insertion. Returns false if the deque is full.
     */
    public boolean offerFirst(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(element);
            return true;
        }
    }

    /**
     * Nonblocking insertion. Returns false if the deque is full.
     */
    public boolean offerLast(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(element);
            return true;
        }
    }

    /**
     * Nonblocking insertion. Throws IllegalStateException if full.
     */
    public void addFirst(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("Deque is full");
            }
            linkFirst(element);
        }
    }

    /**
     * Nonblocking insertion. Throws IllegalStateException if full.
     */
    public void addLast(E element) {
        checkElement(element);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("Deque is full");
            }
            linkLast(element);
        }
    }

    /**
     * Waits for space if full. Interruption while waiting leaves the deque unchanged.
     */
    public void putFirst(E element) throws InterruptedException {
        checkElement(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(element);
        }
    }

    /**
     * Waits for space if full. Interruption while waiting leaves the deque unchanged.
     */
    public void putLast(E element) throws InterruptedException {
        checkElement(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(element);
        }
    }

    /**
     * Nonblocking removal. Returns null if empty.
     */
    public E pollFirst() {
        synchronized (lock) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    /**
     * Nonblocking removal. Returns null if empty.
     */
    public E pollLast() {
        synchronized (lock) {
            return size == 0 ? null : unlinkLast();
        }
    }

    /**
     * Nonblocking removal. Throws IllegalStateException if empty.
     */
    public E removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
        }
    }

    /**
     * Nonblocking removal. Throws IllegalStateException if empty.
     */
    public E removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkLast();
        }
    }

    /**
     * Waits for an element if empty. Interruption while waiting leaves the deque unchanged.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    /**
     * Waits for an element if empty. Interruption while waiting leaves the deque unchanged.
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    /**
     * Returns the first element, or null if empty, without removing it.
     */
    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    /**
     * Returns the last element, or null if empty, without removing it.
     */
    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    /**
     * Returns the first element, throwing IllegalStateException if empty.
     */
    public E getFirst() {
        synchronized (lock) {
            if (first == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return first.item;
        }
    }

    /**
     * Returns the last element, throwing IllegalStateException if empty.
     */
    public E getLast() {
        synchronized (lock) {
            if (last == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return last.item;
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
            if (size != 0) {
                first = null;
                last = null;
                size = 0;
                lock.notifyAll();
            }
        }
    }
}
