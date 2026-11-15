@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object mutex = new Object();
    private Node<E> head;
    private int size;

    /**
     * Adds value to the set.
     *
     * @return true if the value was added, or false if it was already present
     * @throws NullPointerException if value is null
     */
    public boolean add(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (current.value.equals(value)) {
                    return false;
                }
            }

            head = new Node<E>(value, head);
            size++;
            return true;
        }
    }

    /**
     * Removes value from the set.
     *
     * @return true if the value was present and removed
     * @throws NullPointerException if value is null
     */
    public boolean remove(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
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

    /**
     * Returns whether value is present in the set.
     *
     * @throws NullPointerException if value is null
     */
    public boolean contains(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (mutex) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (current.value.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            head = null;
            size = 0;
        }
    }
}
