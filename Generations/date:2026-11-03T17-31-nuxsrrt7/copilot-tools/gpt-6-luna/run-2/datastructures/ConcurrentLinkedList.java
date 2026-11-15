@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<T> {
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
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public void addFirst(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<T> node = new Node<>(value);
            node.next = head;

            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }

            head = node;
            size++;
        }
    }

    public void addLast(T value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<T> node = new Node<>(value);
            node.previous = tail;

            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }

            tail = node;
            size++;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<T> removed = head;
            head = removed.next;

            if (head == null) {
                tail = null;
            } else {
                head.previous = null;
            }

            size--;
            return removed.value;
        }
    }

    public T removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            Node<T> removed = tail;
            tail = removed.previous;

            if (tail == null) {
                head = null;
            } else {
                tail.next = null;
            }

            size--;
            return removed.value;
        }
    }

    public T peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public T peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.value;
        }
    }

    public boolean contains(T value) {
        if (value == null) {
            return false;
        }

        synchronized (lock) {
            for (Node<T> node = head; node != null; node = node.next) {
                if (value.equals(node.value)) {
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
            tail = null;
            size = 0;
        }
    }
}
