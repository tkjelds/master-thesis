@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentLinkedList() {
    }

    /**
     * Appends a value to the end of the list.
     *
     * @return true
     */
    public boolean add(E value) {
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
                tail = node;
            } else {
                tail.next = node;
                tail = node;
            }
            size++;
            return true;
        }
    }

    public void addFirst(E value) {
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
        }
    }

    public void addLast(E value) {
        add(value);
    }

    /**
     * Inserts a value at an index from 0 through size().
     */
    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureCapacity();

            if (index == 0) {
                Node<E> node = new Node<E>(value);
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                Node<E> node = new Node<E>(value);
                node.next = previous.next;
                previous.next = node;
                if (node.next == null) {
                    tail = node;
                }
            }
            size++;
        }
    }

    /**
     * Removes and returns the first value. Throws IllegalStateException if empty.
     */
    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("The list is empty");
            }

            Node<E> removed = head;
            head = removed.next;
            size--;
            if (head == null) {
                tail = null;
            }
            removed.next = null;
            return removed.value;
        }
    }

    /**
     * Removes and returns the last value. Throws IllegalStateException if empty.
     */
    public E removeLast() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("The list is empty");
            }

            if (head == tail) {
                E value = head.value;
                head = null;
                tail = null;
                size = 0;
                return value;
            }

            Node<E> previous = head;
            while (previous.next != tail) {
                previous = previous.next;
            }

            E value = tail.value;
            previous.next = null;
            tail = previous;
            size--;
            return value;
        }
    }

    /**
     * Removes and returns the value at an index.
     */
    public E removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);

            Node<E> removed;
            if (index == 0) {
                removed = head;
                head = removed.next;
                if (head == null) {
                    tail = null;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                removed = previous.next;
                previous.next = removed.next;
                if (removed == tail) {
                    tail = previous;
                }
            }

            size--;
            removed.next = null;
            return removed.value;
        }
    }

    /**
     * Returns the value at an index.
     */
    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    /**
     * Replaces the value at an index and returns the previous value.
     */
    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    /**
     * Returns the first value, or null if the list is empty.
     */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    /**
     * Returns the last value, or null if the list is empty.
     */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.value;
        }
    }

    /**
     * Removes and returns the first value, or null if the list is empty.
     */
    public E pollFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> removed = head;
            head = removed.next;
            size--;
            if (head == null) {
                tail = null;
            }
            removed.next = null;
            return removed.value;
        }
    }

    /**
     * Returns true if the list contains a value equal to the argument.
     * Null values are supported.
     */
    public boolean contains(E value) {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (valuesEqual(current.value, value)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    /**
     * Removes the first value equal to the argument.
     *
     * @return true if a value was removed
     */
    public boolean removeValue(E value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (valuesEqual(current.value, value)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }

                    if (current == tail) {
                        tail = previous;
                    }

                    size--;
                    current.next = null;
                    return true;
                }
                previous = current;
                current = current.next;
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

    private Node<E> nodeAt(int index) {
        Node<E> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Size: " + size
            );
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Size: " + size
            );
        }
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("The list is too large");
        }
    }

    private boolean valuesEqual(E first, E second) {
        return first == null ? second == null : first.equals(second);
    }
}
