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

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public boolean offerFirst(E item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkFirst(item);
            monitor.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkLast(item);
            monitor.notifyAll();
            return true;
        }
    }

    public void putFirst(E item) throws InterruptedException {
        requireNonNull(item);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkFirst(item);
            monitor.notifyAll();
        }
    }

    public void putLast(E item) throws InterruptedException {
        requireNonNull(item);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkLast(item);
            monitor.notifyAll();
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E item = unlinkFirst();
            monitor.notifyAll();
            return item;
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            if (size == 0) {
                return null;
            }
            E item = unlinkLast();
            monitor.notifyAll();
            return item;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E item = unlinkFirst();
            monitor.notifyAll();
            return item;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            E item = unlinkLast();
            monitor.notifyAll();
            return item;
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            Node<E> node = first;
            while (node != null) {
                Node<E> next = node.next;
                node.previous = null;
                node.next = null;
                node = next;
            }
            first = null;
            last = null;
            size = 0;
            monitor.notifyAll();
        }
    }

    private void linkFirst(E item) {
        Node<E> node = new Node<E>(item);
        Node<E> oldFirst = first;
        node.next = oldFirst;
        if (oldFirst == null) {
            last = node;
        } else {
            oldFirst.previous = node;
        }
        first = node;
        size++;
    }

    private void linkLast(E item) {
        Node<E> node = new Node<E>(item);
        Node<E> oldLast = last;
        node.previous = oldLast;
        if (oldLast == null) {
            first = node;
        } else {
            oldLast.next = node;
        }
        last = node;
        size++;
    }

    private E unlinkFirst() {
        Node<E> node = first;
        Node<E> next = node.next;
        first = next;
        if (next == null) {
            last = null;
        } else {
            next.previous = null;
        }
        node.next = null;
        size--;
        return node.item;
    }

    private E unlinkLast() {
        Node<E> node = last;
        Node<E> previous = node.previous;
        last = previous;
        if (previous == null) {
            first = null;
        } else {
            previous.next = null;
        }
        node.previous = null;
        size--;
        return node.item;
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("null elements are not supported");
        }
    }
}
