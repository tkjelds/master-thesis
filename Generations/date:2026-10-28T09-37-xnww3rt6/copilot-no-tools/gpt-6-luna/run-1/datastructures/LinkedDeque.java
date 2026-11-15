@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    public void addFirst(E value) {
        requireNonNull(value);
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

    public void addLast(E value) {
        requireNonNull(value);
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

    public boolean offerFirst(E value) {
        addFirst(value);
        return true;
    }

    public boolean offerLast(E value) {
        addLast(value);
        return true;
    }

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
            node.next = null;
            size--;
            return node.value;
        }
    }

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
            node.previous = null;
            size--;
            return node.value;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.value;
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

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }
}
