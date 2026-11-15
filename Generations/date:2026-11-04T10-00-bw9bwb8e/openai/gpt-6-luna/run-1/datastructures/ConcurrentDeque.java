@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private long count;

    public ConcurrentDeque() {
    }

    /**
     * Adds an element at the front. This deque is unbounded, so the operation
     * does not wait for space. Null elements are not permitted.
     */
    public void addFirst(E element) {
        requireElement(element);
        synchronized (lock) {
            Node<E> node = new Node<E>(element);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Adds an element at the back. This deque is unbounded, so the operation
     * does not wait for space. Null elements are not permitted.
     */
    public void addLast(E element) {
        requireElement(element);
        synchronized (lock) {
            Node<E> node = new Node<E>(element);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            count++;
            lock.notifyAll();
        }
    }

    /**
     * Removes and returns the front element, waiting until one is available.
     */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (first == null) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    /**
     * Removes and returns the back element, waiting until one is available.
     */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (last == null) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    /**
     * Removes and returns the front element, or null if the deque is empty.
     */
    public E pollFirst() {
        synchronized (lock) {
            return first == null ? null : unlinkFirst();
        }
    }

    /**
     * Removes and returns the back element, or null if the deque is empty.
     */
    public E pollLast() {
        synchronized (lock) {
            return last == null ? null : unlinkLast();
        }
    }

    /**
     * Returns the front element, or null if the deque is empty.
     */
    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    /**
     * Returns the back element, or null if the deque is empty.
     */
    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public long size() {
        synchronized (lock) {
            return count;
        }
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            count = 0;
        }
    }

    private E unlinkFirst() {
        Node<E> node = first;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        count--;
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
        count--;
        return node.item;
    }

    private static void requireElement(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }
}
