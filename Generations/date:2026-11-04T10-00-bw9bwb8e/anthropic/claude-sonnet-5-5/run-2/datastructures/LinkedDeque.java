@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final T item;
        Node<T> prev;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    // Single monitor guarding all mutable state. Both "not empty" and "not full"
    // conditions are waited on using this monitor; notifyAll is used on every
    // state change so that waiting producers/consumers re-check their guards.
    private final Object lock = new Object();

    private final int capacity;
    private Node<E> head; // guarded by lock
    private Node<E> tail; // guarded by lock
    private int size;     // guarded by lock

    /** Creates an unbounded deque (capacity Integer.MAX_VALUE). */
    public LinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a bounded deque. Blocking insert operations wait while the deque
     * is full; blocking remove operations wait while the deque is empty.
     */
    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    // ---- internal helpers (must be called while holding lock) ----

    private void linkFirst(E e) {
        Node<E> n = new Node<E>(e);
        if (head == null) {
            head = n;
            tail = n;
        } else {
            n.next = head;
            head.prev = n;
            head = n;
        }
        size++;
    }

    private void linkLast(E e) {
        Node<E> n = new Node<E>(e);
        if (tail == null) {
            head = n;
            tail = n;
        } else {
            n.prev = tail;
            tail.next = n;
            tail = n;
        }
        size++;
    }

    private E unlinkFirst() {
        Node<E> n = head;
        head = n.next;
        if (head == null) {
            tail = null;
        } else {
            head.prev = null;
        }
        n.next = null;
        size--;
        return n.item;
    }

    private E unlinkLast() {
        Node<E> n = tail;
        tail = n.prev;
        if (tail == null) {
            head = null;
        } else {
            tail.next = null;
        }
        n.prev = null;
        size--;
        return n.item;
    }

    // ---- blocking operations ----

    /** Inserts at the front, waiting while the deque is full. */
    public void addFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            linkFirst(e);
            lock.notifyAll();
        }
    }

    /** Inserts at the back, waiting while the deque is full. */
    public void addLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            linkLast(e);
            lock.notifyAll();
        }
    }

    /** Removes and returns the front element, waiting while the deque is empty. */
    public E removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the back element, waiting while the deque is empty. */
    public E removeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    // ---- timed operations ----

    /** Inserts at the front, waiting up to timeoutMillis for space. Returns false on timeout. */
    public boolean offerFirst(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (size >= capacity) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                long ms = remaining / 1000000L;
                int ns = (int) (remaining % 1000000L);
                lock.wait(ms, ns);
            }
            linkFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Inserts at the back, waiting up to timeoutMillis for space. Returns false on timeout. */
    public boolean offerLast(E e, long timeoutMillis) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (size >= capacity) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                long ms = remaining / 1000000L;
                int ns = (int) (remaining % 1000000L);
                lock.wait(ms, ns);
            }
            linkLast(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes the front element, waiting up to timeoutMillis. Returns null on timeout. */
    public E pollFirst(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (size == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                long ms = remaining / 1000000L;
                int ns = (int) (remaining % 1000000L);
                lock.wait(ms, ns);
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes the back element, waiting up to timeoutMillis. Returns null on timeout. */
    public E pollLast(long timeoutMillis) throws InterruptedException {
        synchronized (lock) {
            long deadline = System.nanoTime() + timeoutMillis * 1000000L;
            while (size == 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                long ms = remaining / 1000000L;
                int ns = (int) (remaining % 1000000L);
                lock.wait(ms, ns);
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    // ---- non-blocking operations ----

    /** Inserts at the front if space is available; returns false if full. */
    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size >= capacity) return false;
            linkFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Inserts at the back if space is available; returns false if full. */
    public boolean offerLast(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size >= capacity) return false;
            linkLast(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns the front element, or null if empty. */
    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the back element, or null if empty. */
    public E pollLast() {
        synchronized (lock) {
            if (size == 0) return null;
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    /** Returns the front element without removing it, or null if empty. */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /** Returns the back element without removing it, or null if empty. */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    // ---- queries ----

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

    public boolean contains(Object o) {
        if (o == null) return false;
        synchronized (lock) {
            for (Node<E> n = head; n != null; n = n.next) {
                if (o.equals(n.item)) return true;
            }
            return false;
        }
    }

    /** Removes the first occurrence of the element (front to back). Returns true if removed. */
    public boolean removeFirstOccurrence(Object o) {
        if (o == null) return false;
        synchronized (lock) {
            for (Node<E> n = head; n != null; n = n.next) {
                if (o.equals(n.item)) {
                    if (n.prev == null) {
                        head = n.next;
                    } else {
                        n.prev.next = n.next;
                    }
                    if (n.next == null) {
                        tail = n.prev;
                    } else {
                        n.next.prev = n.prev;
                    }
                    n.prev = null;
                    n.next = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
            }
            return false;
        }
    }

    /** Removes all elements. */
    public void clear() {
        synchronized (lock) {
            Node<E> n = head;
            while (n != null) {
                Node<E> next = n.next;
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

    /** Returns a consistent snapshot of the contents, front to back. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] arr = new Object[size];
            int i = 0;
            for (Node<E> n = head; n != null; n = n.next) {
                arr[i++] = n.item;
            }
            return arr;
        }
    }
}
