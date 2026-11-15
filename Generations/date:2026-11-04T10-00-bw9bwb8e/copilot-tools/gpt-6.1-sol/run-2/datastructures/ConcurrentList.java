/**
 * A thread-safe, dynamically growing list that permits null elements.
 *
 * All operations are serialized using a private monitor. Operations never wait
 * for the list to become nonempty: invalid indices, including reads or removals
 * from an empty list, throw IndexOutOfBoundsException.
 *
 * Individual operations are atomic; sequences of operations are not.
 * Stored elements themselves are not made thread-safe.
 */
@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Node<T> {
        private T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    private final Monitor monitor = new Monitor();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentList() {
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    /**
     * Appends an element. Throws IllegalStateException if the list already
     * contains Integer.MAX_VALUE elements.
     */
    public boolean add(E value) {
        synchronized (monitor) {
            checkCapacity();
            Node<E> node = new Node<E>(value);

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
     * Inserts at an index in the inclusive range [0, size()].
     */
    public void add(int index, E value) {
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            checkCapacity();

            Node<E> node = new Node<E>(value);
            if (index == 0) {
                node.next = head;
                head = node;
                if (size == 0) {
                    tail = node;
                }
            } else {
                Node<E> previous = nodeAt(index - 1);
                node.next = previous.next;
                previous.next = node;
                if (index == size) {
                    tail = node;
                }
            }
            size++;
        }
    }

    public E get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    /**
     * Replaces the element at index and returns its previous value.
     */
    public E set(int index, E value) {
        synchronized (monitor) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    /**
     * Removes and returns the element at index.
     */
    public E remove(int index) {
        synchronized (monitor) {
            checkElementIndex(index);

            Node<E> removed;
            if (index == 0) {
                removed = head;
                head = removed.next;
                if (size == 1) {
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

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    // Private helpers are called only while holding monitor.
    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void checkCapacity() {
        if (size == 2147483647) {
            throw new IllegalStateException("Maximum list size reached");
        }
    }

    private Node<E> nodeAt(int index) {
        Node<E> node = head;
        for (int i = 0; i < index; i++) {
            node = node.next;
        }
        return node;
    }
}
