@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private long size;

    public LinkedDeque() {
    }

    public void addFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<>(item);
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

    public void addLast(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<>(item);
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

    public boolean offerFirst(E item) {
        addFirst(item);
        return true;
    }

    public boolean offerLast(E item) {
        addLast(item);
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

            size--;
            node.next = null;
            node.item = null;
            return node.item;
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

            size--;
            node.previous = null;
            node.item = null;
            return node.item;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
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
            first = null;
            last = null;
            size = 0;
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }
}
