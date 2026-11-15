@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Node<E> first;
    private Node<E> last;
    private int size;

    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    public LinkedDeque() {
    }

    public void addFirst(E item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<E> node = new Node<E>(item);
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
            Node<E> node = new Node<E>(item);
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

    // Removal and peek operations return null immediately when the deque is empty.
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
            return node.item;
        }
    }

    public E removeFirst() {
        return pollFirst();
    }

    public E removeLast() {
        return pollLast();
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

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("null elements are not allowed");
        }
    }
}
