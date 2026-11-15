@ConcurrentBag.ThreadSafe
public final class ConcurrentBag<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private int size;

    public ConcurrentBag() {
    }

    public boolean add(E value) {
        synchronized (lock) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Bag is full");
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
                if (same(current.value, value)) {
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
            Node<E> current = head;
            while (current != null) {
                if (same(current.value, value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int count(E value) {
        synchronized (lock) {
            int occurrences = 0;
            Node<E> current = head;
            while (current != null) {
                if (same(current.value, value)) {
                    occurrences++;
                }
                current = current.next;
            }
            return occurrences;
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

    private boolean same(E first, E second) {
        return first == null ? second == null : first.equals(second);
    }
}
