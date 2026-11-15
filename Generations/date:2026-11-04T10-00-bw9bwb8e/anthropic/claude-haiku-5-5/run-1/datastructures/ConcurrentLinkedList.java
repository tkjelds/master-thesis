@ConcurrentLinkedList.ThreadSafe
public final class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    /*
     * Concurrency policy:
     *  - All state (head, tail, size) is guarded by the private monitor 'lock'.
     *  - Blocking operations (addFirst, addLast, removeFirst, removeLast) wait while the
     *    list is full (add) or empty (remove). They are guarded by while loops and every
     *    state change calls notifyAll() because waiters of both kinds share one monitor.
     *  - Non-blocking "try" variants return immediately with a failure indicator
     *    (false or null) instead of waiting.
     *  - Interruption while waiting propagates as InterruptedException; the state is
     *    left unchanged in that case.
     *  - Null elements are rejected with NullPointerException, since null is used as the
     *    "empty" sentinel by tryRemoveFirst/tryRemoveLast.
     */

    private final Object lock = new Object();
    private final int capacity;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public ConcurrentLinkedList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    // ---------- Blocking insertion ----------

    public void addFirst(T value) throws InterruptedException {
        checkNotNull(value);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(value);
            lock.notifyAll();
        }
    }

    public void addLast(T value) throws InterruptedException {
        checkNotNull(value);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(value);
            lock.notifyAll();
        }
    }

    // ---------- Non-blocking insertion ----------

    public boolean tryAddFirst(T value) {
        checkNotNull(value);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(value);
            lock.notifyAll();
            return true;
        }
    }

    public boolean tryAddLast(T value) {
        checkNotNull(value);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(value);
            lock.notifyAll();
            return true;
        }
    }

    // ---------- Blocking removal ----------

    public T removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T value = unlinkFirst();
            lock.notifyAll();
            return value;
        }
    }

    public T removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T value = unlinkLast();
            lock.notifyAll();
            return value;
        }
    }

    // ---------- Non-blocking removal ----------

    /** Returns the first element, or null if the list is empty. */
    public T tryRemoveFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T value = unlinkFirst();
            lock.notifyAll();
            return value;
        }
    }

    /** Returns the last element, or null if the list is empty. */
    public T tryRemoveLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T value = unlinkLast();
            lock.notifyAll();
            return value;
        }
    }

    /** Removes the first occurrence of value. Returns true if an element was removed. */
    public boolean remove(T value) {
        checkNotNull(value);
        synchronized (lock) {
            Node<T> prev = null;
            Node<T> cur = head;
            while (cur != null) {
                if (value.equals(cur.value)) {
                    if (prev == null) {
                        head = cur.next;
                    } else {
                        prev.next = cur.next;
                    }
                    if (cur == tail) {
                        tail = prev;
                    }
                    cur.next = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
                prev = cur;
                cur = cur.next;
            }
            return false;
        }
    }

    // ---------- Queries ----------

    public boolean contains(T value) {
        checkNotNull(value);
        synchronized (lock) {
            for (Node<T> cur = head; cur != null; cur = cur.next) {
                if (value.equals(cur.value)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns the element at index (0-based), or throws IndexOutOfBoundsException. */
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
            }
            Node<T> cur = head;
            for (int i = 0; i < index; i++) {
                cur = cur.next;
            }
            return cur.value;
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

    // ---------- Internal helpers (must be called while holding lock) ----------

    private void linkFirst(T value) {
        Node<T> node = new Node<>(value);
        node.next = head;
        head = node;
        if (tail == null) {
            tail = node;
        }
        size++;
    }

    private void linkLast(T value) {
        Node<T> node = new Node<>(value);
        if (tail == null) {
            head = node;
        } else {
            tail.next = node;
        }
        tail = node;
        size++;
    }

    private T unlinkFirst() {
        Node<T> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        node.next = null;
        size--;
        return node.value;
    }

    private T unlinkLast() {
        Node<T> node = tail;
        if (head == tail) {
            head = null;
            tail = null;
        } else {
            Node<T> cur = head;
            while (cur.next != tail) {
                cur = cur.next;
            }
            cur.next = null;
            tail = cur;
        }
        size--;
        return node.value;
    }

    private static void checkNotNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
