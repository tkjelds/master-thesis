/**
 * A thread-safe, unbounded, singly linked list.
 *
 * Null elements are permitted. Indices are zero-based. Operations never wait
 * for elements or capacity: invalid indices throw IndexOutOfBoundsException,
 * and insertion at the maximum representable size throws IllegalStateException.
 *
 * Every operation holds the same private monitor for its entire execution.
 * Separate operations are individually atomic, not collectively atomic.
 */
@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int size;

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

    public boolean add(E value) {
        synchronized (monitor) {
            insert(size, value);
            return true;
        }
    }

    public void add(int index, E value) {
        synchronized (monitor) {
            insert(index, value);
        }
    }

    public E get(int index) {
        synchronized (monitor) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (monitor) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (monitor) {
            checkElementIndex(index);

            Node<E> previous = null;
            Node<E> removed;

            if (index == 0) {
                removed = head;
                head = removed.next;
            } else {
                previous = nodeAt(index - 1);
                removed = previous.next;
                previous.next = removed.next;
            }

            if (removed == tail) {
                tail = previous;
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

    /**
     * Returns an independent, shallow snapshot in list order.
     * The elements themselves are not copied or synchronized.
     */
    public Object[] toArray() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < size; i++) {
                result[i] = current.value;
                current = current.next;
            }
            return result;
        }
    }

    // Private helpers are called only while holding monitor.
    private void insert(int index, E value) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException();
        }
        if (size == 2147483647) {
            throw new IllegalStateException("Maximum list size reached");
        }

        Node<E> added = new Node<E>(value);

        if (index == 0) {
            added.next = head;
            head = added;
        } else {
            Node<E> previous = index == size ? tail : nodeAt(index - 1);
            added.next = previous.next;
            previous.next = added;
        }

        if (index == size) {
            tail = added;
        }
        size++;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private Node<E> nodeAt(int index) {
        Node<E> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
    }
}
