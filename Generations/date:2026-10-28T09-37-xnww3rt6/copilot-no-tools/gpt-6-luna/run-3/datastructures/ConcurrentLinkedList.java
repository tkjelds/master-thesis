@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> previous;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public void add(E value) {
        addLast(value);
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

            E value = head.value;
            unlink(head);
            return value;
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            E value = tail.value;
            unlink(tail);
            return value;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            Node<E> node = nodeAt(index);
            E value = node.value;
            unlink(node);
            return value;
        }
    }

    public boolean remove(E value) {
        requireNonNull(value);

        synchronized (lock) {
            Node<E> node = head;
            while (node != null) {
                if (node.value.equals(value)) {
                    unlink(node);
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).value;
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

    public boolean contains(E value) {
        requireNonNull(value);

        synchronized (lock) {
            Node<E> node = head;
            while (node != null) {
                if (node.value.equals(value)) {
                    return true;
                }
                node = node.next;
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
            Node<E> node = head;
            while (node != null) {
                Node<E> next = node.next;
                node.previous = null;
                node.next = null;
                node = next;
            }

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

    private void unlink(Node<E> node) {
        if (node.previous == null) {
            head = node.next;
        } else {
            node.previous.next = node.next;
        }

        if (node.next == null) {
            tail = node.previous;
        } else {
            node.next.previous = node.previous;
        }

        node.previous = null;
        node.next = null;
        size--;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    private void requireNonNull(E value) {
        if (value == null) {
            throw new NullPointerException("Null values are not supported");
        }
    }
}
