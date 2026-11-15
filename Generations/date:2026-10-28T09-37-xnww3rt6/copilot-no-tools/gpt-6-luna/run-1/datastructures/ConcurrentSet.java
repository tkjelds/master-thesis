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

    public boolean add(E value) {
        synchronized (lock) {
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(node.value, value)) {
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
                if (equal(current.value, value)) {
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
            for (Node<E> node = head; node != null; node = node.next) {
                if (equal(node.value, value)) {
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
        return first == second || (first != null && first.equals(second));
    }
}
