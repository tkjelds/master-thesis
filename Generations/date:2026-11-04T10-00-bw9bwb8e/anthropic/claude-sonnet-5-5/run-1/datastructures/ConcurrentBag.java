import java.lang.Object;

/**
 * A thread-safe bag (multiset) with an optional capacity bound.
 *
 * Policy:
 *  - The bag holds elements with multiplicity. The capacity bounds the total
 *    number of elements, counting duplicates.
 *  - add(...) blocks while the bag is full. tryAdd(...) returns false instead of blocking.
 *  - take() blocks while the bag is empty. tryTake() returns null instead of blocking.
 *  - remove(item) is non-blocking and returns false if the item is absent.
 *  - takeSpecific(item) blocks until at least one occurrence of the item is present.
 *  - Null elements are rejected with NullPointerException.
 *  - Blocking methods throw InterruptedException. The state is left unchanged when that happens.
 *
 * All state is guarded by a single private monitor. Every state change calls
 * notifyAll, and every wait is guarded by a while loop.
 */
@ConcurrentBag.ThreadSafe
public class ConcurrentBag<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final T item;
        final int hash;
        int count;
        Node<T> hashNext; // chain within a bucket
        Node<T> prev;     // doubly linked list of distinct elements
        Node<T> next;

        Node(T item, int hash) {
            this.item = item;
            this.hash = hash;
            this.count = 0;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by lock.
    private Node<T>[] table;
    private Node<T> head;
    private Node<T> tail;
    private int distinct;
    private int size;

    public ConcurrentBag() {
        this(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.table = (Node<T>[]) new Node[16];
    }

    // ---------- public operations ----------

    /** Adds one occurrence, blocking while the bag is full. */
    public void add(T item) throws InterruptedException {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            insertLocked(item);
            lock.notifyAll();
        }
    }

    /** Adds one occurrence if there is room; returns false if the bag is full. */
    public boolean tryAdd(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            insertLocked(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns an arbitrary element, blocking while the bag is empty. */
    public T take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            T result = removeOneLocked(head);
            lock.notifyAll();
            return result;
        }
    }

    /** Removes and returns an arbitrary element, or null if the bag is empty. */
    public T tryTake() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            T result = removeOneLocked(head);
            lock.notifyAll();
            return result;
        }
    }

    /** Removes one occurrence of item if present; returns whether it was removed. */
    public boolean remove(Object item) {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            Node<T> n = findLocked(item);
            if (n == null) {
                return false;
            }
            removeOneLocked(n);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes one occurrence of item, blocking until one is present. */
    public void takeSpecific(Object item) throws InterruptedException {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            Node<T> n;
            while ((n = findLocked(item)) == null) {
                lock.wait();
            }
            removeOneLocked(n);
            lock.notifyAll();
        }
    }

    /** Returns the number of occurrences of item. */
    public int count(Object item) {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            Node<T> n = findLocked(item);
            return n == null ? 0 : n.count;
        }
    }

    public boolean contains(Object item) {
        return count(item) > 0;
    }

    /** Total number of elements, including duplicates. */
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

    /** Removes all elements and returns how many were removed. */
    @SuppressWarnings("unchecked")
    public int clear() {
        synchronized (lock) {
            int removed = size;
            table = (Node<T>[]) new Node[16];
            head = null;
            tail = null;
            distinct = 0;
            size = 0;
            lock.notifyAll();
            return removed;
        }
    }

    /** Returns a consistent snapshot of the contents, with duplicates repeated. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int i = 0;
            for (Node<T> n = head; n != null; n = n.next) {
                for (int c = 0; c < n.count; c++) {
                    result[i++] = n.item;
                }
            }
            return result;
        }
    }

    // ---------- internals (call only while holding lock) ----------

    private static int spread(int h) {
        return h ^ (h >>> 16);
    }

    private Node<T> findLocked(Object item) {
        int h = spread(item.hashCode());
        Node<T> n = table[h & (table.length - 1)];
        while (n != null) {
            if (n.hash == h && (n.item == item || n.item.equals(item))) {
                return n;
            }
            n = n.hashNext;
        }
        return null;
    }

    private void insertLocked(T item) {
        Node<T> n = findLocked(item);
        if (n == null) {
            int h = spread(item.hashCode());
            n = new Node<T>(item, h);
            if (distinct + 1 > (table.length >> 1) + (table.length >> 2)) {
                resizeLocked();
            }
            int idx = h & (table.length - 1);
            n.hashNext = table[idx];
            table[idx] = n;
            n.prev = tail;
            if (tail == null) {
                head = n;
            } else {
                tail.next = n;
            }
            tail = n;
            distinct++;
        }
        n.count++;
        size++;
    }

    private T removeOneLocked(Node<T> n) {
        T item = n.item;
        n.count--;
        size--;
        if (n.count == 0) {
            // unlink from bucket chain
            int idx = n.hash & (table.length - 1);
            Node<T> cur = table[idx];
            Node<T> before = null;
            while (cur != null && cur != n) {
                before = cur;
                cur = cur.hashNext;
            }
            if (cur == n) {
                if (before == null) {
                    table[idx] = n.hashNext;
                } else {
                    before.hashNext = n.hashNext;
                }
            }
            n.hashNext = null;
            // unlink from list
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
            distinct--;
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private void resizeLocked() {
        int newLen = table.length << 1;
        Node<T>[] nt = (Node<T>[]) new Node[newLen];
        for (Node<T> n = head; n != null; n = n.next) {
            int idx = n.hash & (newLen - 1);
            n.hashNext = nt[idx];
            nt[idx] = n;
        }
        table = nt;
    }
}
