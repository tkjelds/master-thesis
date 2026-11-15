@ThreadSafeLinkedList.ThreadSafe
public final class ThreadSafeLinkedList<T> {
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
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public void addFirst(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = new Node<>(item);
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

    public void addLast(T item) {
        requireNonNull(item);
        synchronized (lock) {
            Node<T> node = new Node<>(item);
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

    public void add(T item) {
        addLast(item);
    }

    public T peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public T peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public T removeFirst() {
        synchronized (lock) {
            return head == null ? null : unlink(head);
        }
    }

    public T removeLast() {
        synchronized (lock) {
            return tail == null ? null : unlink(tail);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).item;
        }
    }

    public T removeAt(int index) {
        synchronized (lock) {
            checkIndex(index);
            return unlink(nodeAt(index));
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
            Node<T> current = head;
            while (current != null) {
                Node<T> next = current.next;
                current.item = null;
                current.previous = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    private T unlink(Node<T> node) {
        Node<T> previous = node.previous;
        Node<T> next = node.next;

        if (previous == null) {
            head = next;
        } else {
            previous.next = next;
        }

        if (next == null) {
            tail = previous;
        } else {
            next.previous = previous;
        }

        T item = node.item;
        node.item = null;
        node.previous = null;
        node.next = null;
        size--;
        return item;
    }

    private Node<T> nodeAt(int index) {
        if (index < size / 2) {
            Node<T> current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current;
        }

        Node<T> current = tail;
        for (int i = size - 1; i > index; i--) {
            current = current.previous;
        }
        return current;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }
}
