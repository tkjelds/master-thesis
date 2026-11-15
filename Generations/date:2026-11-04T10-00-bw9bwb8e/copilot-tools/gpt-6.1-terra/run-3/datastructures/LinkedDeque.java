@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    private void requireItem(E item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    private void linkFirst(E item) {
        Node<E> node = new Node<E>(item);
        node.next = first;
        if (first == null) {
            last = node;
        } else {
            first.previous = node;
        }
        first = node;
        size++;
        lock.notifyAll();
    }

    private void linkLast(E item) {
        Node<E> node = new Node<E>(item);
        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
        size++;
        lock.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> node = first;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        node.next = null;
        size--;
        lock.notifyAll();
        return node.item;
    }

    private E unlinkLast() {
        Node<E> node = last;
        last = node.previous;
        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }
        node.previous = null;
        size--;
        lock.notifyAll();
        return node.item;
    }

    // Add operations throw when full; offer operations return false.
    public void addFirst(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("Deque is full");
            }
            linkFirst(item);
        }
    }

    public void addLast(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                throw new IllegalStateException("Deque is full");
            }
            linkLast(item);
        }
    }

    public boolean offerFirst(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(item);
            return true;
        }
    }

    public boolean offerLast(E item) {
        requireItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(item);
            return true;
        }
    }

    // Put operations wait for space and are interruptible.
    public void putFirst(E item) throws InterruptedException {
        requireItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(item);
        }
    }

    public void putLast(E item) throws InterruptedException {
        requireItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(item);
        }
    }

    // Remove operations throw when empty; poll and peek operations return null.
    public E removeFirst() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (lock) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkLast();
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (lock) {
            return size == 0 ? null : unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return last == null ? null : last.item;
        }
    }

    // Take operations wait for an element and are interruptible.
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size != 0) {
                first = null;
                last = null;
                size = 0;
                lock.notifyAll();
            }
        }
    }
}
