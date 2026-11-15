/**
 * A thread-safe, null-permitting indexed list.
 *
 * Operations execute atomically using this list's intrinsic monitor.
 * Operations never wait for elements or capacity: invalid indices throw
 * IndexOutOfBoundsException, and exceeding the maximum size throws
 * IllegalStateException.
 */
@ConcurrentList.ThreadSafe
public final class ConcurrentList<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T value;
        Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentList() {
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public boolean add(E value) {
        synchronized (this) {
            checkCapacity();
            append(value);
            return true;
        }
    }

    public void add(int index, E value) {
        synchronized (this) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException();
            }
            checkCapacity();

            if (index == size) {
                append(value);
            } else if (index == 0) {
                head = new Node<E>(value, head);
                size++;
            } else {
                Node<E> previous = nodeAt(index - 1);
                previous.next = new Node<E>(value, previous.next);
                size++;
            }
        }
    }

    public E get(int index) {
        synchronized (this) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (this) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E remove(int index) {
        synchronized (this) {
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
        synchronized (this) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    /**
     * Returns an independent array containing a snapshot of the elements.
     * Element objects themselves are not copied.
     */
    public Object[] toArray() {
        synchronized (this) {
            Object[] result = new Object[size];
            Node<E> current = head;
            for (int i = 0; i < size; i++) {
                result[i] = current.value;
                current = current.next;
            }
            return result;
        }
    }

    // Private helpers are called only while holding this list's monitor.
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
        Node<E> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current;
    }

    private void append(E value) {
        Node<E> node = new Node<E>(value, null);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }
}
