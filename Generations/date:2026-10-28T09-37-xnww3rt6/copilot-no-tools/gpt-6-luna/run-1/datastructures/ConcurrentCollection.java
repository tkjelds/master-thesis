@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentCollection() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentCollection(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean add(E element) throws InterruptedException {
        requireNonNull(element);

        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }

            Node<E> node = new Node<E>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean tryAdd(E element) {
        requireNonNull(element);

        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }

            Node<E> node = new Node<E>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public E remove() throws InterruptedException {
        synchronized (monitor) {
            while (head == null) {
                monitor.wait();
            }

            E value = removeHead();
            monitor.notifyAll();
            return value;
        }
    }

    public E poll() {
        synchronized (monitor) {
            if (head == null) {
                return null;
            }

            E value = removeHead();
            monitor.notifyAll();
            return value;
        }
    }

    public E peek() {
        synchronized (monitor) {
            return head == null ? null : head.value;
        }
    }

    public boolean contains(Object element) {
        synchronized (monitor) {
            Node<E> current = head;
            while (current != null) {
                if (equal(current.value, element)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean remove(Object element) {
        synchronized (monitor) {
            if (element == null) {
                return false;
            }

            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (equal(current.value, element)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }

                    if (current == tail) {
                        tail = previous;
                    }

                    current.next = null;
                    size--;
                    monitor.notifyAll();
                    return true;
                }

                previous = current;
                current = current.next;
            }
            return false;
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

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            tail = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            Node<E> current = head;
            int index = 0;

            while (current != null) {
                result[index++] = current.value;
                current = current.next;
            }
            return result;
        }
    }

    private E removeHead() {
        Node<E> removed = head;
        head = removed.next;
        removed.next = null;
        size--;

        if (head == null) {
            tail = null;
        }

        return removed.value;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }

    private static boolean equal(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }
}
