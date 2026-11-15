@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value, Node<E> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private long size;

    public void add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            head = new Node<E>(value, head);
            size++;
        }
    }

    /**
     * Removes one occurrence of the value, returning false if it is absent.
     */
    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (current.value.equals(value)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    size--;
                    return true;
                }

                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (current.value.equals(value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public long size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            size = 0;
        }
    }
}
