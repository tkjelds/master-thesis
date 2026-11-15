@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /**
     * Adds an element to the end of the list.
     *
     * @return true after the element is added
     * @throws IllegalArgumentException if item is null
     * @throws IllegalStateException if the list cannot grow further
     */
    public boolean add(E item) {
        return addLast(item);
    }

    /**
     * Adds an element to the beginning of the list.
     *
     * @throws IllegalArgumentException if item is null
     * @throws IllegalStateException if the list cannot grow further
     */
    public void addFirst(E item) {
        checkItem(item);
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(item);
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

    /**
     * Adds an element to the end of the list.
     *
     * @return true after the element is added
     * @throws IllegalArgumentException if item is null
     * @throws IllegalStateException if the list cannot grow further
     */
    public boolean addLast(E item) {
        checkItem(item);
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(item);
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

    /**
     * Removes and returns the first element, or null if the list is empty.
     */
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
            return removed.item;
        }
    }

    /**
     * Removes and returns the last element, or null if the list is empty.
     */
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
            return removed.item;
        }
    }

    /**
     * Returns the first element, or null if the list is empty.
     */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /**
     * Returns the last element, or null if the list is empty.
     */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    /**
     * Returns the element at the specified zero-based position.
     *
     * @throws IndexOutOfBoundsException if the position is invalid
     */
    public E get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).item;
        }
    }

    /**
     * Replaces and returns the element at the specified zero-based position.
     *
     * @throws IllegalArgumentException if item is null
     * @throws IndexOutOfBoundsException if the position is invalid
     */
    public E set(int index, E item) {
        checkItem(item);
        synchronized (lock) {
            checkIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.item;
            node = new Node<E>(item);
            node.previous = nodeAt(index).previous;
            node.next = nodeAt(index).next;

            if (node.previous == null) {
                head = node;
            } else {
                node.previous.next = node;
            }

            if (node.next == null) {
                tail = node;
            } else {
                node.next.previous = node;
            }

            return previous;
        }
    }

    /**
     * Removes and returns the element at the specified zero-based position.
     *
     * @throws IndexOutOfBoundsException if the position is invalid
     */
    public E removeAt(int index) {
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

            removed.previous = null;
            removed.next = null;
            size--;
            return removed.item;
        }
    }

    /**
     * Returns true if an element equal to item is present.
     * Null elements are not supported.
     */
    public boolean contains(E item) {
        if (item == null) {
            return false;
        }

        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (item.equals(current.item)) {
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
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.previous = null;
                current.next = null;
                current = next;
            }

            head = null;
            tail = null;
            size = 0;
        }
    }

    private void checkItem(E item) {
        if (item == null) {
            throw new IllegalArgumentException("Null elements are not supported");
        }
    }

    private void checkCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List is at maximum capacity");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
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
