@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    public ConcurrentLinkedList() {
    }

    public void addFirst(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<>(value);
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

    public void addLast(E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = new Node<>(value);
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

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> removed = head;
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

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            Node<E> removed = tail;
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

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).value;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            Node<E> removed = nodeAt(index);

            if (removed.previous == null) {
                head = removed.next;
            } else {
                removed.previous.next = removed.next;
            }

            if (removed.next == null) {
                tail = removed.previous;
            } else {
                removed.next.previous = removed.previous;
            }

            size--;
            return removed.value;
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

    private Node<E> nodeAt(int index) {
        if (index < size / 2) {
            Node<E> node = head;
            for (int i = 0; i < index; i++) {
                node = node.next;
            }
            return node;
        }

        Node<E> node = tail;
        for (int i = size - 1; i > index; i--) {
            node = node.previous;
        }
        return node;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void requireNonNull(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }
}
