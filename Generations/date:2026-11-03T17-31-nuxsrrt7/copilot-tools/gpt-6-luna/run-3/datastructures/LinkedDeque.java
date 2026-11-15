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
    private int count;

    public boolean offerFirst(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            if (count == Integer.MAX_VALUE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            node.next = first;
            if (first == null) {
                last = node;
            } else {
                first.previous = node;
            }
            first = node;
            count++;
            return true;
        }
    }

    public boolean offerLast(E value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }

        synchronized (lock) {
            if (count == Integer.MAX_VALUE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            node.previous = last;
            if (last == null) {
                first = node;
            } else {
                last.next = node;
            }
            last = node;
            count++;
            return true;
        }
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
            count--;
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
            count--;
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
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return count == 0;
        }
    }

    public void clear() {
        synchronized (lock) {
            first = null;
            last = null;
            count = 0;
        }
    }
}
