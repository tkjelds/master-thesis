/**
 * A thread-safe, unbounded doubly linked list.
 *
 * Null elements are permitted. Operations never wait for elements or capacity.
 * Removing or reading an endpoint of an empty list throws IllegalStateException.
 * Invalid indices throw IndexOutOfBoundsException.
 *
 * All list state is protected by a private monitor. Nodes never escape the list.
 * Stored elements themselves are not made thread-safe.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E value;
        Node<E> previous;
        Node<E> next;

        Node(E value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentLinkedList() {
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

    /** Appends an element and returns true. */
    public boolean add(E value) {
        synchronized (monitor) {
            insertBefore(null, value);
            return true;
        }
    }

    public void addFirst(E value) {
        synchronized (monitor) {
            insertBefore(head, value);
        }
    }

    public void addLast(E value) {
        synchronized (monitor) {
            insertBefore(null, value);
        }
    }

    /** Inserts at an index in the inclusive range [0, size()]. */
    public void add(int index, E value) {
        synchronized (monitor) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Invalid insertion index");
            }
            insertBefore(index == size ? null : nodeAt(index), value);
        }
    }

    public E getFirst() {
        synchronized (monitor) {
            requireNonEmpty();
            return head.value;
        }
    }

    public E getLast() {
        synchronized (monitor) {
            requireNonEmpty();
            return tail.value;
        }
    }

    public E get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    /** Replaces an element and returns its previous value. */
    public E set(int index, E value) {
        synchronized (monitor) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E removeFirst() {
        synchronized (monitor) {
            requireNonEmpty();
            return unlink(head);
        }
    }

    public E removeLast() {
        synchronized (monitor) {
            requireNonEmpty();
            return unlink(tail);
        }
    }

    public E remove(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return unlink(nodeAt(index));
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    /*
     * Private helpers are called only while holding monitor.
     * A null successor means insertion at the end.
     */
    private void insertBefore(Node<E> successor, E value) {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("Maximum list size reached");
        }

        Node<E> node = new Node<E>(value);
        Node<E> predecessor =
                successor == null ? tail : successor.previous;

        node.previous = predecessor;
        node.next = successor;

        if (predecessor == null) {
            head = node;
        } else {
            predecessor.next = node;
        }

        if (successor == null) {
            tail = node;
        } else {
            successor.previous = node;
        }

        size++;
    }

    private E unlink(Node<E> node) {
        Node<E> predecessor = node.previous;
        Node<E> successor = node.next;

        if (predecessor == null) {
            head = successor;
        } else {
            predecessor.next = successor;
        }

        if (successor == null) {
            tail = predecessor;
        } else {
            successor.previous = predecessor;
        }

        E value = node.value;
        node.value = null;
        node.previous = null;
        node.next = null;
        size--;
        return value;
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

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Invalid element index");
        }
    }

    private void requireNonEmpty() {
        if (size == 0) {
            throw new IllegalStateException("List is empty");
        }
    }
}
