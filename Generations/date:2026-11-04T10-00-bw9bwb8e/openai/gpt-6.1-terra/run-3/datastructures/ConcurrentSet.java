/**
 * A thread-safe set that permits null elements.
 *
 * Operations do not wait for elements or capacity. The set grows as needed,
 * subject to available memory and the maximum size representable by an int.
 * Elements are compared using equals; their equality behavior should remain
 * stable while they are stored in the set.
 */
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

    private final Object lock = new Object();
    private Node<E> head;
    private int size;
    private long version;

    public boolean add(E value) {
        synchronized (lock) {
            if (find(value) != null) {
                return false;
            }
            if (size == 2147483647) {
                throw new OutOfMemoryError("Set size limit reached");
            }
            head = new Node<E>(value, head);
            size++;
            version++;
            return true;
        }
    }

    public boolean contains(E value) {
        synchronized (lock) {
            return find(value) != null;
        }
    }

    public boolean remove(E value) {
        synchronized (lock) {
            Node<E> target = find(value);
            if (target == null) {
                return false;
            }

            Node<E> previous = null;
            Node<E> current = head;
            while (current != target) {
                previous = current;
                current = current.next;
            }

            if (previous == null) {
                head = current.next;
            } else {
                previous.next = current.next;
            }
            size--;
            version++;
            return true;
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

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                head = null;
                size = 0;
                version++;
            }
        }
    }

    // Called only while holding lock. Restart if equals reentrantly modifies
    // the set, so that the result refers to the current structure.
    private Node<E> find(E value) {
        while (true) {
            long observedVersion = version;
            Node<E> current = head;

            while (current != null) {
                boolean matches = value == current.value
                        || (value != null && value.equals(current.value));
                if (version != observedVersion) {
                    break;
                }
                if (matches) {
                    return current;
                }
                current = current.next;
            }

            if (version == observedVersion) {
                return null;
            }
        }
    }
}
