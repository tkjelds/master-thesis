@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final T value;
        Node<T> prev;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by 'lock'.
    private Node<T> head;
    private Node<T> tail;
    private int size;

    /**
     * Creates an unbounded list.
     */
    public ConcurrentLinkedList() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded list.
     * Policy: blocking adds wait while the list is full; blocking removes wait while it is empty.
     * Non-blocking variants (tryAdd*, poll*, peek*) return immediately.
     * Null elements are not permitted.
     */
    public ConcurrentLinkedList(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    private static boolean equalValues(Object a, Object b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    // ---------------- Blocking operations ----------------

    /** Inserts at the front, blocking while the list is full. */
    public void addFirst(T item) throws InterruptedException {
        checkNotNull(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirstLocked(item);
            lock.notifyAll();
        }
    }

    /** Inserts at the back, blocking while the list is full. */
    public void addLast(T item) throws InterruptedException {
        checkNotNull(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLastLocked(item);
            lock.notifyAll();
        }
    }

    /** Removes and returns the first element, blocking while the list is empty. */
    public T removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T value = unlinkFirstLocked();
            lock.notifyAll();
            return value;
        }
    }

    /** Removes and returns the last element, blocking while the list is empty. */
    public T removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T value = unlinkLastLocked();
            lock.notifyAll();
            return value;
        }
    }

    // ---------------- Non-blocking operations ----------------

    /** Attempts to insert at the front. Returns false if the list is full. */
    public boolean tryAddFirst(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirstLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Attempts to insert at the back. Returns false if the list is full. */
    public boolean tryAddLast(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLastLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the first element, or null if empty. */
    public T pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T value = unlinkFirstLocked();
            lock.notifyAll();
            return value;
        }
    }

    /** Removes and returns the last element, or null if empty. */
    public T pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T value = unlinkLastLocked();
            lock.notifyAll();
            return value;
        }
    }

    /** Returns the first element without removing it, or null if empty. */
    public T peekFirst() {
        synchronized (lock) {
            return (head == null) ? null : head.value;
        }
    }

    /** Returns the last element without removing it, or null if empty. */
    public T peekLast() {
        synchronized (lock) {
            return (tail == null) ? null : tail.value;
        }
    }

    /** Removes the first occurrence of the given element. Returns true if removed. */
    public boolean remove(Object o) {
        synchronized (lock) {
            Node<T> cur = head;
            while (cur != null) {
                if (equalValues(cur.value, o)) {
                    unlinkNodeLocked(cur);
                    lock.notifyAll();
                    return true;
                }
                cur = cur.next;
            }
            return false;
        }
    }

    /** Returns true if the list contains the given element. */
    public boolean contains(Object o) {
        synchronized (lock) {
            Node<T> cur = head;
            while (cur != null) {
                if (equalValues(cur.value, o)) {
                    return true;
                }
                cur = cur.next;
            }
            return false;
        }
    }

    /** Returns the element at the given index. */
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

    /** Returns the current number of elements. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the list is currently empty. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all elements, waking any blocked threads so they can re-check their conditions. */
    public void clear() {
        synchronized (lock) {
            Node<T> cur = head;
            while (cur != null) {
                Node<T> next = cur.next;
                cur.prev = null;
                cur.next = null;
                cur = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of the elements in order. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<T> cur = head;
            int i = 0;
            while (cur != null) {
                result[i++] = cur.value;
                cur = cur.next;
            }
            return result;
        }
    }

    // ---------------- Lock-held helpers (caller must hold 'lock') ----------------

    private void linkFirstLocked(T item) {
        Node<T> n = new Node<>(item);
        n.next = head;
        if (head != null) {
            head.prev = n;
        } else {
            tail = n;
        }
        head = n;
        size++;
    }

    private void linkLastLocked(T item) {
        Node<T> n = new Node<>(item);
        n.prev = tail;
        if (tail != null) {
            tail.next = n;
        } else {
            head = n;
        }
        tail = n;
        size++;
    }

    private T unlinkFirstLocked() {
        Node<T> n = head;
        unlinkNodeLocked(n);
        return n.value;
    }

    private T unlinkLastLocked() {
        Node<T> n = tail;
        unlinkNodeLocked(n);
        return n.value;
    }

    private void unlinkNodeLocked(Node<T> n) {
        if (n.prev != null) {
            n.prev.next = n.next;
        } else {
            head = n.next;
        }
        if (n.next != null) {
            n.next.prev = n.prev;
        } else {
            tail = n.prev;
        }
        n.prev = null;
        n.next = null;
        size--;
    }
}
