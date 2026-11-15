@ThreadSafeLinkedDeque.ThreadSafe
public final class ThreadSafeLinkedDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    /*
     * Blocking policy:
     *  - putFirst/putLast block while the deque is full (size == capacity).
     *  - takeFirst/takeLast block while the deque is empty.
     *  - offerFirst/offerLast never block; they return false if the deque is full.
     *  - pollFirst/pollLast never block; they return null if the deque is empty.
     *  - peekFirst/peekLast never block; they return null if the deque is empty.
     *  - Blocking methods throw InterruptedException if the waiting thread is interrupted.
     *  - Null elements are rejected with NullPointerException.
     *
     * All state is guarded by the monitor of 'lock'. Every state change
     * calls notifyAll so that both producers and consumers re-check their
     * guard conditions inside a while loop.
     */

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ThreadSafeLinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public ThreadSafeLinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
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

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    // ---------- Blocking insertion ----------

    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(e);
            lock.notifyAll();
        }
    }

    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(e);
            lock.notifyAll();
        }
    }

    // ---------- Non-blocking insertion ----------

    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(e);
            lock.notifyAll();
            return true;
        }
    }

    // ---------- Blocking removal ----------

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    // ---------- Non-blocking removal ----------

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    // ---------- Inspection ----------

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<E> n = head;
            while (n != null) {
                Node<E> next = n.next;
                n.item = null;
                n.prev = null;
                n.next = null;
                n = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Node<E> n = head;
            boolean first = true;
            while (n != null) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(n.item);
                first = false;
                n = n.next;
            }
            sb.append(']');
            return sb.toString();
        }
    }

    // ---------- Internal helpers (caller must hold lock) ----------

    private void linkFirst(E e) {
        Node<E> node = new Node<>(e);
        if (head == null) {
            head = node;
            tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
        size++;
    }

    private void linkLast(E e) {
        Node<E> node = new Node<>(e);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            node.prev = tail;
            tail.next = node;
            tail = node;
        }
        size++;
    }

    private E unlinkFirst() {
        Node<E> node = head;
        E item = node.item;
        Node<E> next = node.next;
        node.item = null;
        node.next = null;
        head = next;
        if (next == null) {
            tail = null;
        } else {
            next.prev = null;
        }
        size--;
        return item;
    }

    private E unlinkLast() {
        Node<E> node = tail;
        E item = node.item;
        Node<E> prev = node.prev;
        node.item = null;
        node.prev = null;
        tail = prev;
        if (prev == null) {
            head = null;
        } else {
            prev.next = null;
        }
        size--;
        return item;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
