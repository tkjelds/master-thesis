import java.lang.*;

/**
 * A thread-safe bounded (or effectively unbounded) bag (multiset).
 *
 * Policy:
 *  - add(e) blocks while the bag is full (total element count == capacity).
 *  - take() blocks while the bag is empty.
 *  - take(e) blocks until at least one occurrence of e is present.
 *  - tryAdd / tryTake / remove(e) are non-blocking and return a failure result instead.
 *  - Blocking operations are interruptible and throw InterruptedException.
 *  - Null elements are rejected with NullPointerException.
 *
 * All state is guarded by a single private monitor. Every state change calls notifyAll,
 * and all waits are guarded by while loops.
 */
@ConcurrentBag.ThreadSafe
public class ConcurrentBag<E> {
    @interface ThreadSafe {}


    private static final class Node {
        final Object item;
        final int hash;
        int count;
        int index; // position in dense array
        Node next; // chain in hash bucket

        Node(Object item, int hash) {
            this.item = item;
            this.hash = hash;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // All fields below are guarded by lock.
    private Node[] table;
    private Node[] dense;
    private int distinct;
    private int total;

    public ConcurrentBag() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentBag(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.table = new Node[16];
        this.dense = new Node[16];
        this.distinct = 0;
        this.total = 0;
    }

    // ---------- internal helpers (must be called holding lock) ----------

    private static int spread(Object o) {
        int h = o.hashCode();
        return h ^ (h >>> 16);
    }

    private Node find(Object o, int hash) {
        Node n = table[hash & (table.length - 1)];
        while (n != null) {
            if (n.hash == hash && (n.item == o || n.item.equals(o))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    private void resizeTable() {
        Node[] old = table;
        Node[] nt = new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node n = old[i];
            while (n != null) {
                Node nx = n.next;
                int idx = n.hash & (nt.length - 1);
                n.next = nt[idx];
                nt[idx] = n;
                n = nx;
            }
        }
        table = nt;
    }

    private void insertOne(Object o) {
        int hash = spread(o);
        Node n = find(o, hash);
        if (n != null) {
            n.count++;
        } else {
            if (distinct + 1 > (table.length >> 1) + (table.length >> 2)) {
                resizeTable();
            }
            if (distinct == dense.length) {
                Node[] nd = new Node[dense.length * 2];
                for (int i = 0; i < distinct; i++) {
                    nd[i] = dense[i];
                }
                dense = nd;
            }
            n = new Node(o, hash);
            n.count = 1;
            int idx = hash & (table.length - 1);
            n.next = table[idx];
            table[idx] = n;
            n.index = distinct;
            dense[distinct++] = n;
        }
        total++;
    }

    private void removeOne(Node n) {
        n.count--;
        total--;
        if (n.count == 0) {
            int idx = n.hash & (table.length - 1);
            Node cur = table[idx];
            Node prev = null;
            while (cur != null && cur != n) {
                prev = cur;
                cur = cur.next;
            }
            if (cur != null) {
                if (prev == null) {
                    table[idx] = cur.next;
                } else {
                    prev.next = cur.next;
                }
            }
            Node last = dense[distinct - 1];
            dense[n.index] = last;
            last.index = n.index;
            dense[distinct - 1] = null;
            distinct--;
            n.next = null;
        }
    }

    // ---------- public API ----------

    /** Adds an element, blocking while the bag is full. */
    public void add(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            while (total >= capacity) {
                lock.wait();
            }
            insertOne(e);
            lock.notifyAll();
        }
    }

    /** Adds an element if there is room; returns false if full. */
    public boolean tryAdd(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (total >= capacity) {
                return false;
            }
            insertOne(e);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes and returns an arbitrary element, blocking while the bag is empty. */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        synchronized (lock) {
            while (total == 0) {
                lock.wait();
            }
            Node n = dense[distinct - 1];
            Object item = n.item;
            removeOne(n);
            lock.notifyAll();
            return (E) item;
        }
    }

    /** Removes and returns an arbitrary element, or null if the bag is empty. */
    @SuppressWarnings("unchecked")
    public E tryTake() {
        synchronized (lock) {
            if (total == 0) {
                return null;
            }
            Node n = dense[distinct - 1];
            Object item = n.item;
            removeOne(n);
            lock.notifyAll();
            return (E) item;
        }
    }

    /** Removes one occurrence of the given element, blocking until one is present. */
    public void take(Object o) throws InterruptedException {
        if (o == null) {
            throw new NullPointerException();
        }
        int hash = spread(o);
        synchronized (lock) {
            Node n;
            while ((n = find(o, hash)) == null) {
                lock.wait();
            }
            removeOne(n);
            lock.notifyAll();
        }
    }

    /** Removes one occurrence of the element if present; returns whether one was removed. */
    public boolean remove(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        int hash = spread(o);
        synchronized (lock) {
            Node n = find(o, hash);
            if (n == null) {
                return false;
            }
            removeOne(n);
            lock.notifyAll();
            return true;
        }
    }

    /** Removes all occurrences of the element; returns the number removed. */
    public int removeAll(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        int hash = spread(o);
        synchronized (lock) {
            Node n = find(o, hash);
            if (n == null) {
                return 0;
            }
            int c = n.count;
            while (n.count > 0) {
                removeOne(n);
            }
            lock.notifyAll();
            return c;
        }
    }

    /** Returns the number of occurrences of the element. */
    public int count(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
        int hash = spread(o);
        synchronized (lock) {
            Node n = find(o, hash);
            return n == null ? 0 : n.count;
        }
    }

    public boolean contains(Object o) {
        return count(o) > 0;
    }

    /** Total number of elements, counting duplicates. */
    public int size() {
        synchronized (lock) {
            return total;
        }
    }

    /** Number of distinct elements. */
    public int distinctSize() {
        synchronized (lock) {
            return distinct;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return total == 0;
        }
    }

    public int capacity() {
        return capacity;
    }

    /** Removes all elements. */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            for (int i = 0; i < distinct; i++) {
                dense[i] = null;
            }
            distinct = 0;
            total = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of all elements (with duplicates). */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[total];
            int k = 0;
            for (int i = 0; i < distinct; i++) {
                Node n = dense[i];
                for (int j = 0; j < n.count; j++) {
                    result[k++] = n.item;
                }
            }
            return result;
        }
    }
}
