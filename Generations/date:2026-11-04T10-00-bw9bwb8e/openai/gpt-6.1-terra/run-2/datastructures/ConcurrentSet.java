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

    /*
     * Null elements are supported. Element equality must remain stable while
     * an element is in the set. Equality methods must not modify this set.
     *
     * Operations do not wait for elements or capacity, although they may wait
     * to acquire the monitor. An insertion beyond the representable size throws
     * IllegalStateException.
     */
    public boolean add(E value) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(value, node.value)) {
                    return false;
                }
            }

            if (size == 2147483647) {
                throw new IllegalStateException("Set size limit reached");
            }

            head = new Node<E>(value, head);
            size++;
            return true;
        }
    }

    public boolean remove(Object value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> node = head;

            while (node != null) {
                if (equal(value, node.value)) {
                    if (previous == null) {
                        head = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return true;
                }
                previous = node;
                node = node.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(value, node.value)) {
                    return true;
                }
            }
            return false;
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
            head = null;
            size = 0;
        }
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }
}
