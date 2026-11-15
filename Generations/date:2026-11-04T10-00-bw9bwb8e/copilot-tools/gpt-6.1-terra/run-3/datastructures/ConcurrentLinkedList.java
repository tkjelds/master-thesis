/**
 * A thread-safe, unbounded singly linked list. All operations are non-blocking
 * apart from acquiring the list's monitor. Null elements are permitted.
 *
 * Removing from an empty list throws IllegalStateException. Invalid indices
 * throw IndexOutOfBoundsException. Insertion at the maximum representable size
 * throws IllegalStateException.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E value;
        Node<E> next;

        Node(E value) {
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

    public void add(E value) {
        addLast(value);
    }

    public void addFirst(E value) {
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(value);
            node.next = head;
            head = node;
            if (size == 0) {
                tail = node;
            }
            size++;
        }
    }

    public void addLast(E value) {
        synchronized (lock) {
            checkCapacity();
            Node<E> node = new Node<E>(value);
            if (size == 0) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
        }
    }

    public void add(int index, E value) {
        synchronized (lock) {
            checkPositionIndex(index);
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
        synchronized (lock) {
            checkElementIndex(index);
            return nodeAt(index).value;
        }
    }

    public E set(int index, E value) {
        synchronized (lock) {
            checkElementIndex(index);
            Node<E> node = nodeAt(index);
            E previous = node.value;
            node.value = value;
            return previous;
        }
    }

    public E removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("List is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("List is empty");
            }
            if (size == 1) {
                return unlinkFirst();
            }
            Node<E> previous = nodeAt(size - 2);
            E value = tail.value;
            previous.next = null;
            tail = previous;
            size--;
            return value;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            checkElementIndex(index);
            if (index == 0) {
                return unlinkFirst();
            }
            Node<E> previous = nodeAt(index - 1);
            Node<E> removed = previous.next;
            previous.next = removed.next;
            if (removed == tail) {
                tail = previous;
            }
            size--;
            return removed.value;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
        }
    }

    private E unlinkFirst() {
        Node<E> removed = head;
        head = removed.next;
        size--;
        if (size == 0) {
            tail = null;
        }
        return removed.value;
    }

    private Node<E> nodeAt(int index) {
        Node<E> node = head;
        for (int i = 0; i < index; i++) {
            node = node.next;
        }
        return node;
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void checkPositionIndex(int index) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + size);
        }
    }

    private void checkCapacity() {
        if (size == Integer.MAX_VALUE) {
            throw new IllegalStateException("List has reached its maximum size");
        }
    }
}
