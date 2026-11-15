@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<E> {
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
    private int size;

    public boolean add(E value) {
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (equal(value, current.value)) {
                    return false;
                }
            }

            head = new Node<E>(value, head);
            size++;
            return true;
        }
    }

    public boolean remove(E value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (equal(value, current.value)) {
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
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (equal(value, current.value)) {
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

    private static boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
