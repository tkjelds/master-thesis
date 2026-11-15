@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private long size;

    public boolean addFirst(E value) {
        return offerFirst(value);
    }

    public boolean addLast(E value) {
        return offerLast(value);
    }

    public boolean offerFirst(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.next = head;

            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }

            head = node;
            size++;
            return true;
        }
    }

    public boolean offerLast(E value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.previous = tail;

            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }

            tail = node;
            size++;
            return true;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> node = head;
            head = node.next;

            if (head == null) {
                tail = null;
            } else {
                head.previous = null;
            }

            node.next = null;
            size--;
            return node.value;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            Node<E> node = tail;
            tail = node.previous;

            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }

            node.previous = null;
            size--;
            return node.value;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.value;
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
            head = null;
            tail = null;
            size = 0;
        }
    }
}
