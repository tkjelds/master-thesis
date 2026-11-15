@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;
        private Node<E> previous;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

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

    public void insert(int index, E value) {
        requireNonNull(value);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            if (index == 0) {
                Node<E> node = new Node<>(value);
                node.next = head;
                if (head == null) {
                    tail = node;
                } else {
                    head.previous = node;
                }
                head = node;
                size++;
                return;
            }
            if (index == size) {
                Node<E> node = new Node<>(value);
                node.previous = tail;
                tail.next = node;
                tail = node;
                size++;
                return;
            }

            Node<E> next = nodeAt(index);
            Node<E> previous = next.previous;
            Node<E> node = new Node<>(value);
            node.previous = previous;
            node.next = next;
            previous.next = node;
            next.previous = node;
            size++;
        }
    }

    public E get(int index) {
        synchronized (lock) {
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        requireNonNull(value);
        synchronized (lock) {
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return unlink(head);
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            return unlink(tail);
        }
    }

    public E removeAt(int index) {
        synchronized (lock) {
            return unlink(nodeAt(index));
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.value = null;
                current.next = null;
                current.previous = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < size; i++) {
                result[i] = current.value;
                current = current.next;
            }
            return result;
        }
    }

    private Node<E> nodeAt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

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

    private E unlink(Node<E> node) {
        Node<E> previous = node.previous;
        Node<E> next = node.next;

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

        size--;
        E value = node.value;
        node.value = null;
        node.next = null;
        node.previous = null;
        return value;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }
    }
}
