@ConcurrentCollection.ThreadSafe
public final class ConcurrentCollection<E> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

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

        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }

            append(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean tryAdd(E element) {
        requireNonNull(element);

        synchronized (lock) {
            if (size == capacity) {
                return false;
            }

            append(element);
            lock.notifyAll();
            return true;
        }
    }

    public E remove() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }

            E element = removeHead();
            lock.notifyAll();
            return element;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }

            E element = removeHead();
            lock.notifyAll();
            return element;
        }
    }

    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                if (current.value == element || current.value.equals(element)) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }

        synchronized (lock) {
            Node<E> previous = null;
            Node<E> current = head;

            while (current != null) {
                if (current.value == element || current.value.equals(element)) {
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
                    lock.notifyAll();
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

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
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

    private void append(E element) {
        Node<E> node = new Node<E>(element);

        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }

        tail = node;
        size++;
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

    private static void requireNonNull(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not supported");
        }
    }
}
