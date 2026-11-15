@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
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

    /** Adds an element to the end of this unbounded list. */
    public void add(E value) {
        synchronized (lock) {
            ensureCapacity();
            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    /** Inserts an element at an index from 0 through size(). */
    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);
            ensureCapacity();

            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (tail == null) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
                if (node.next == null) {
                    tail = node;
                }
            }
            size++;
        }
    }

    /** Returns the element at an index from 0 through size() - 1. */
    public E get(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    /** Replaces the element at index and returns its previous value. */
    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    /** Removes and returns the element at index. */
    public E remove(int index) {
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
            removed.next = null;
            E value = removed.value;
            removed.value = null;
            return value;
        }
    }

    /** Removes the first element equal to value, if present. */
    public boolean remove(Object value) {
        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (equal(value, current.value)) {
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
                    current.value = null;
                    return true;
                }
                previous = current;
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(Object value) {
        synchronized (lock) {
            for (Node<E> current = head; current != null; current = current.next) {
                if (equal(value, current.value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns the index of the first element equal to value, or -1 if absent. */
    public int indexOf(Object value) {
        synchronized (lock) {
            int index = 0;
            for (Node<E> current = head; current != null; current = current.next) {
                if (equal(value, current.value)) {
                    return index;
                }
                index++;
            }
            return -1;
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
                current.value = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    /** Returns a point-in-time snapshot; changes to it do not affect this list. */
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
        Node<E> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private void ensureCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("list is too large");
        }
    }

    private static boolean equal(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
    }
}
