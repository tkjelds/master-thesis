@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /**
     * Adds a value to the front of the list. Null values are not permitted.
     */
    public boolean addFirst(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            Node<E> node = new Node<E>(value);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
            return true;
        }
    }

    /**
     * Adds a value to the end of the list. Null values are not permitted.
     */
    public boolean addLast(E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
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

    /**
     * Adds a value at the specified position. Valid positions are from zero
     * through the current size.
     */
    public void add(int index, E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else if (index == size) {
                tail.next = node;
                tail = node;
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
            }
            size++;
        }
    }

    /**
     * Returns the value at the specified position.
     */
    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    /**
     * Replaces the value at the specified position and returns the old value.
     */
    public E set(int index, E value) {
        if (value == null) {
            throw new NullPointerException("value");
        }

        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E oldValue = node.value;
            // Nodes have immutable values, so replace this node in the chain.
            Node<E> replacement = new Node<E>(value);
            replacement.next = node.next;

            if (index == 0) {
                head = replacement;
            } else {
                nodeAt(index - 1).next = replacement;
            }
            if (tail == node) {
                tail = replacement;
            }
            return oldValue;
        }
    }

    /**
     * Removes and returns the value at the specified position.
     */
    public E removeAt(int index) {
        synchronized (lock) {
            checkElementIndex(index);

            Node<E> removed;
            if (index == 0) {
                removed = head;
                head = head.next;
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
            return removed.value;
        }
    }

    /**
     * Removes and returns the first value, or returns null if the list is empty.
     */
    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            Node<E> removed = head;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            size--;
            return removed.value;
        }
    }

    /**
     * Removes and returns the last value, or returns null if the list is empty.
     */
    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }

            Node<E> removed = tail;
            if (head == tail) {
                head = null;
                tail = null;
            } else {
                Node<E> previous = head;
                while (previous.next != tail) {
                    previous = previous.next;
                }
                previous.next = null;
                tail = previous;
            }
            size--;
            return removed.value;
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
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }
}
