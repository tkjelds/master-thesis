/**
 * A thread-safe doubly linked list.
 *
 * Null elements are permitted. Removal and peek operations return null when
 * empty and never block. Insertions return false if the list already contains
 * 2,147,483,647 elements; otherwise they return true.
 *
 * Each public operation is atomic with respect to other public operations.
 */
@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<E> {
    @interface ThreadSafe {}

    private static final int MAX_SIZE = 2147483647;

    private static final class Monitor {
    }

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Monitor monitor = new Monitor();
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentLinkedList() {
    }

    public boolean add(E value) {
        return addLast(value);
    }

    public boolean addFirst(E value) {
        synchronized (monitor) {
            if (size == MAX_SIZE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            node.next = head;

            if (head == null) {
                tail = node;
            } else {
                head.previous = node;
            }

            head = node;
            size++;
            return true;
        }
    }

    public boolean addLast(E value) {
        synchronized (monitor) {
            if (size == MAX_SIZE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
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

    public E removeFirst() {
        synchronized (monitor) {
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
            return removed.value;
        }
    }

    public E removeLast() {
        synchronized (monitor) {
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
            return removed.value;
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return tail == null ? null : tail.value;
        }
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

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
        }
    }
}
