@ConcurrentDeque.ThreadSafe
public final class ConcurrentDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean offerFirst(E item) {
        requireNonNull(item);
        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            linkFirst(item);
            notifyAll();
            return true;
        }
    }

    public boolean offerLast(E item) {
        requireNonNull(item);
        synchronized (this) {
            if (size == capacity) {
                return false;
            }
            linkLast(item);
            notifyAll();
            return true;
        }
    }

    public void putFirst(E item) throws InterruptedException {
        requireNonNull(item);
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            linkFirst(item);
            notifyAll();
        }
    }

    public void putLast(E item) throws InterruptedException {
        requireNonNull(item);
        synchronized (this) {
            while (size == capacity) {
                wait();
            }
            linkLast(item);
            notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (this) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (this) {
            return size == 0 ? null : unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E item = unlinkFirst();
            notifyAll();
            return item;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                wait();
            }
            E item = unlinkLast();
            notifyAll();
            return item;
        }
    }

    public E peekFirst() {
        synchronized (this) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (this) {
            return tail == null ? null : tail.item;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (this) {
            return capacity - size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
            head = null;
            tail = null;
            size = 0;
            notifyAll();
        }
    }

    private void linkFirst(E item) {
        Node<E> node = new Node<E>(item);
        node.next = head;
        if (head == null) {
            tail = node;
        } else {
            head.previous = node;
        }
        head = node;
        size++;
    }

    private void linkLast(E item) {
        Node<E> node = new Node<E>(item);
        node.previous = tail;
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    private E unlinkFirst() {
        Node<E> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        } else {
            head.previous = null;
        }
        size--;
        return node.item;
    }

    private E unlinkLast() {
        Node<E> node = tail;
        tail = node.previous;
        if (tail == null) {
            head = null;
        } else {
            tail.next = null;
        }
        size--;
        return node.item;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new IllegalArgumentException("null elements are not supported");
        }
    }
}
