@LinkedList.ThreadSafe
public final class LinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private final T value;
        private Node<T> next;
        private Node<T> previous;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public LinkedList() {
    }

    public void add(E value) {
        addLast(value);
    }

    public void addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
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
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
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

            removed.next = null;
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

            removed.previous = null;
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

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).value;
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
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.next = null;
                current.previous = null;
                current = next;
            }

            head = null;
            tail = null;
            size = 0;
        }
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is too large");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Size: " + size
            );
        }
    }

    private Node<E> nodeAt(int index) {
        if (index < size / 2) {
            Node<E> current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current;
        }

        Node<E> current = tail;
        for (int i = size - 1; i > index; i--) {
            current = current.previous;
        }
        return current;
    }
}
