@ThreadSafeLinkedDeque.ThreadSafe
public final class ThreadSafeLinkedDeque<E> {
    @interface ThreadSafe {}


    /*
     * Concurrency policy:
     *  - All state (head, tail, size) is guarded by the intrinsic monitor 'lock'.
     *  - Capacity is fixed at construction and must be positive.
     *  - Null elements are rejected with NullPointerException.
     *  - Blocking operations (putFirst, putLast, takeFirst, takeLast) wait on 'lock'
     *    inside while loops. Every successful state change calls notifyAll(), so both
     *    producers waiting for space and consumers waiting for elements are woken and
     *    re-check their guard condition. Waits are interruptible: an interrupted waiter
     *    throws InterruptedException and leaves the deque unchanged.
     *  - Non-blocking operations (offerFirst, offerLast, pollFirst, pollLast, peekFirst,
     *    peekLast) return immediately: offers return false when full, polls and peeks
     *    return null when empty.
     */

    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // Guarded by lock
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ThreadSafeLinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    public ThreadSafeLinkedDeque() {
        this(Integer.MAX_VALUE);
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

    /** Inserts at the front, blocking while the deque is full. */
    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(e);
        }
    }

    /** Inserts at the back, blocking while the deque is full. */
    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(e);
        }
    }

    /** Inserts at the front if space is available; returns false if full. */
    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(e);
            return true;
        }
    }

    /** Inserts at the back if space is available; returns false if full. */
    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(e);
            return true;
        }
    }

    /** Removes from the front, blocking while the deque is empty. */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkFirst();
        }
    }

    /** Removes from the back, blocking while the deque is empty. */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return unlinkLast();
        }
    }

    /** Removes from the front; returns null if empty. */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return unlinkFirst();
        }
    }

    /** Removes from the back; returns null if empty. */
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return unlinkLast();
        }
    }

    /** Returns the front element without removing it; null if empty. */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /** Returns the back element without removing it; null if empty. */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            while (size > 0) {
                unlinkFirst();
            }
        }
    }

    // ---- Helpers: callers must hold 'lock' ----

    private void linkFirst(E e) {
        Node<E> n = new Node<>(e);
        if (head == null) {
            head = n;
            tail = n;
        } else {
            n.next = head;
            head.prev = n;
            head = n;
        }
        size++;
        lock.notifyAll();
    }

    private void linkLast(E e) {
        Node<E> n = new Node<>(e);
        if (tail == null) {
            head = n;
            tail = n;
        } else {
            n.prev = tail;
            tail.next = n;
            tail = n;
        }
        size++;
        lock.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> n = head;
        E item = n.item;
        head = n.next;
        if (head == null) {
            tail = null;
        } else {
            head.prev = null;
        }
        n.item = null;
        n.next = null;
        size--;
        lock.notifyAll();
        return item;
    }

    private E unlinkLast() {
        Node<E> n = tail;
        E item = n.item;
        tail = n.prev;
        if (tail == null) {
            head = null;
        } else {
            tail.next = null;
        }
        n.item = null;
        n.prev = null;
        size--;
        lock.notifyAll();
        return item;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
