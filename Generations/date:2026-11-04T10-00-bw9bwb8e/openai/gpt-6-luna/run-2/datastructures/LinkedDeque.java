@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedDeque() {
    }

    /**
     * Adds an element to the front. Null elements are not permitted.
     */
    public void addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.next = first;

            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }

            first = node;
            size++;
        }
    }

    /**
     * Adds an element to the back. Null elements are not permitted.
     */
    public void addLast(E value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.previous = last;

            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }

            last = node;
            size++;
        }
    }

    /**
     * Returns and removes the front element, or null if the deque is empty.
     * This operation never blocks.
     */
    public E pollFirst() {
        synchronized (lock) {
            if (first == null) {
                return null;
            }

            Node<E> node = first;
            first = node.next;

            if (first == null) {
                last = null;
            } else {
                first.previous = null;
            }

            size--;
            node.next = null;
            return node.value;
        }
    }

    /**
     * Returns and removes the back element, or null if the deque is empty.
     * This operation never blocks.
     */
    public E pollLast() {
        synchronized (lock) {
            if (last == null) {
                return null;
            }

            Node<E> node = last;
            last = node.previous;

            if (last == null) {
                first = null;
            } else {
                last.next = null;
            }

            size--;
            node.previous = null;
            return node.value;
        }
    }

    /**
     * Returns the front element, or null if the deque is empty.
     */
    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    /**
     * Returns the back element, or null if the deque is empty.
     */
    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Removes all elements.
     */
    public void clear() {
        synchronized (lock) {
            Node<E> node = first;
            while (node != null) {
                Node<E> next = node.next;
                node.previous = null;
                node.next = null;
                node = next;
            }

            first = null;
            last = null;
            size = 0;
        }
    }
}
