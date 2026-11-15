import java.lang.*;

/**
 * A thread-safe sorted map implemented as a skip list.
 *
 * Concurrency design:
 *  - A readers-writer lock is built from one intrinsic monitor with wait/notifyAll.
 *    Read-only operations (get, containsKey, size, firstKey, floorKey, ...) run
 *    concurrently with each other. Mutating operations (put, remove, clear, ...)
 *    run exclusively.
 *  - Writers are preferred: new readers wait while a writer is waiting, so writers do not starve.
 *  - Waiting is uninterruptible: an interrupt is remembered and restored after
 *    the lock is acquired.
 *  - Every access to the skip list's mutable state happens while holding the
 *    read or write lock. Entering and leaving the monitor gives the
 *    happens-before edges needed for visibility and safe publication.
 *
 * Policy for operations that cannot complete immediately: none of the map
 * operations block on the data structure's contents. Lookups on missing keys
 * return null (or false). They only wait briefly for lock access.
 *
 * Null keys and null values are not permitted (NullPointerException).
 */
@ConcurrentSkipListMap.ThreadSafe
public class ConcurrentSkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<K, V> {
        final K key;
        V value;
        final Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = (Node<K, V>[]) new Node[level];
        }
    }

    // ---- skip list state, guarded by the readers-writer lock ----
    private final Node<K, V> head = new Node<K, V>(null, null, MAX_LEVEL);
    private int levelCount = 1;
    private int size = 0;
    private long seed;

    // ---- readers-writer lock state, guarded by monitor 'mon' ----
    private final Object mon = new Object();
    private int activeReaders = 0;
    private boolean writerActive = false;
    private int waitingWriters = 0;

    public ConcurrentSkipListMap() {
        seed = 0x9E3779B97F4A7C15L ^ System.nanoTime();
        if (seed == 0) {
            seed = 88172645463325252L;
        }
    }

    // ------------------------------------------------------------------
    // Lock implementation
    // ------------------------------------------------------------------

    private void lockRead() {
        boolean interrupted = false;
        synchronized (mon) {
            while (writerActive || waitingWriters > 0) {
                try {
                    mon.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            activeReaders++;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void unlockRead() {
        synchronized (mon) {
            activeReaders--;
            if (activeReaders == 0) {
                mon.notifyAll();
            }
        }
    }

    private void lockWrite() {
        boolean interrupted = false;
        synchronized (mon) {
            waitingWriters++;
            try {
                while (writerActive || activeReaders > 0) {
                    try {
                        mon.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                waitingWriters--;
            }
            writerActive = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void unlockWrite() {
        synchronized (mon) {
            writerActive = false;
            mon.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers (caller must hold appropriate lock)
    // ------------------------------------------------------------------

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL) {
            long x = seed;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            seed = x;
            if ((x & 1L) == 0) {
                break;
            }
            lvl++;
        }
        return lvl;
    }

    /** Returns the node with the given key, or null. */
    private Node<K, V> findNode(K key) {
        Node<K, V> cur = head;
        for (int i = levelCount - 1; i >= 0; i--) {
            Node<K, V> nx = cur.next[i];
            while (nx != null && nx.key.compareTo(key) < 0) {
                cur = nx;
                nx = cur.next[i];
            }
        }
        Node<K, V> cand = cur.next[0];
        if (cand != null && cand.key.compareTo(key) == 0) {
            return cand;
        }
        return null;
    }

    /** Returns the greatest node with key strictly less than the given key (or head). */
    private Node<K, V> findLessThan(K key) {
        Node<K, V> cur = head;
        for (int i = levelCount - 1; i >= 0; i--) {
            Node<K, V> nx = cur.next[i];
            while (nx != null && nx.key.compareTo(key) < 0) {
                cur = nx;
                nx = cur.next[i];
            }
        }
        return cur;
    }

    private Node<K, V> lastNode() {
        Node<K, V> cur = head;
        for (int i = levelCount - 1; i >= 0; i--) {
            while (cur.next[i] != null) {
                cur = cur.next[i];
            }
        }
        return cur == head ? null : cur;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Associates value with key; returns the previous value or null. */
    @SuppressWarnings("unchecked")
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        lockWrite();
        try {
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> cur = head;
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                if (i < levelCount) {
                    Node<K, V> nx = cur.next[i];
                    while (nx != null && nx.key.compareTo(key) < 0) {
                        cur = nx;
                        nx = cur.next[i];
                    }
                }
                update[i] = cur;
            }
            Node<K, V> cand = cur.next[0];
            if (cand != null && cand.key.compareTo(key) == 0) {
                V old = cand.value;
                cand.value = value;
                return old;
            }
            int lvl = randomLevel();
            if (lvl > levelCount) {
                for (int i = levelCount; i < lvl; i++) {
                    update[i] = head;
                }
                levelCount = lvl;
            }
            Node<K, V> n = new Node<K, V>(key, value, lvl);
            for (int i = 0; i < lvl; i++) {
                n.next[i] = update[i].next[i];
                update[i].next[i] = n;
            }
            size++;
            return null;
        } finally {
            unlockWrite();
        }
    }

    /** Inserts only if absent; returns existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        lockWrite();
        try {
            Node<K, V> n = findNode(key);
            if (n != null) {
                return n.value;
            }
        } finally {
            unlockWrite();
        }
        // Not found under the lock above; re-do atomically below.
        return putIfAbsentSlow(key, value);
    }

    @SuppressWarnings("unchecked")
    private V putIfAbsentSlow(K key, V value) {
        lockWrite();
        try {
            Node<K, V> existing = findNode(key);
            if (existing != null) {
                return existing.value;
            }
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> cur = head;
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                if (i < levelCount) {
                    Node<K, V> nx = cur.next[i];
                    while (nx != null && nx.key.compareTo(key) < 0) {
                        cur = nx;
                        nx = cur.next[i];
                    }
                }
                update[i] = cur;
            }
            int lvl = randomLevel();
            if (lvl > levelCount) {
                for (int i = levelCount; i < lvl; i++) {
                    update[i] = head;
                }
                levelCount = lvl;
            }
            Node<K, V> n = new Node<K, V>(key, value, lvl);
            for (int i = 0; i < lvl; i++) {
                n.next[i] = update[i].next[i];
                update[i].next[i] = n;
            }
            size++;
            return null;
        } finally {
            unlockWrite();
        }
    }

    /** Returns the value for key, or null if absent. */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockRead();
        try {
            Node<K, V> n = findNode(key);
            return n == null ? null : n.value;
        } finally {
            unlockRead();
        }
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** Removes key; returns the removed value or null if absent. */
    @SuppressWarnings("unchecked")
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockWrite();
        try {
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> cur = head;
            for (int i = levelCount - 1; i >= 0; i--) {
                Node<K, V> nx = cur.next[i];
                while (nx != null && nx.key.compareTo(key) < 0) {
                    cur = nx;
                    nx = cur.next[i];
                }
                update[i] = cur;
            }
            Node<K, V> target = cur.next[0];
            if (target == null || target.key.compareTo(key) != 0) {
                return null;
            }
            for (int i = 0; i < levelCount; i++) {
                if (update[i].next[i] != target) {
                    break;
                }
                update[i].next[i] = target.next[i];
            }
            while (levelCount > 1 && head.next[levelCount - 1] == null) {
                levelCount--;
            }
            size--;
            return target.value;
        } finally {
            unlockWrite();
        }
    }

    /** Removes key only if currently mapped to value (compared with equals). */
    public boolean remove(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        lockWrite();
        try {
            Node<K, V> n = findNode(key);
            if (n == null || !n.value.equals(value)) {
                return false;
            }
        } finally {
            unlockWrite();
        }
        return removeIfEquals(key, value);
    }

    @SuppressWarnings("unchecked")
    private boolean removeIfEquals(K key, V value) {
        lockWrite();
        try {
            Node<K, V> n = findNode(key);
            if (n == null || !n.value.equals(value)) {
                return false;
            }
            Node<K, V>[] update = (Node<K, V>[]) new Node[MAX_LEVEL];
            Node<K, V> cur = head;
            for (int i = levelCount - 1; i >= 0; i--) {
                Node<K, V> nx = cur.next[i];
                while (nx != null && nx.key.compareTo(key) < 0) {
                    cur = nx;
                    nx = cur.next[i];
                }
                update[i] = cur;
            }
            Node<K, V> target = cur.next[0];
            for (int i = 0; i < levelCount; i++) {
                if (update[i].next[i] != target) {
                    break;
                }
                update[i].next[i] = target.next[i];
            }
            while (levelCount > 1 && head.next[levelCount - 1] == null) {
                levelCount--;
            }
            size--;
            return true;
        } finally {
            unlockWrite();
        }
    }

    /** Replaces the value only if key is present; returns old value or null. */
    public V replace(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        lockWrite();
        try {
            Node<K, V> n = findNode(key);
            if (n == null) {
                return null;
            }
            V old = n.value;
            n.value = value;
            return old;
        } finally {
            unlockWrite();
        }
    }

    public int size() {
        lockRead();
        try {
            return size;
        } finally {
            unlockRead();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void clear() {
        lockWrite();
        try {
            for (int i = 0; i < MAX_LEVEL; i++) {
                head.next[i] = null;
            }
            levelCount = 1;
            size = 0;
        } finally {
            unlockWrite();
        }
    }

    /** Smallest key, or null if empty. */
    public K firstKey() {
        lockRead();
        try {
            Node<K, V> n = head.next[0];
            return n == null ? null : n.key;
        } finally {
            unlockRead();
        }
    }

    /** Largest key, or null if empty. */
    public K lastKey() {
        lockRead();
        try {
            Node<K, V> n = lastNode();
            return n == null ? null : n.key;
        } finally {
            unlockRead();
        }
    }

    /** Greatest key strictly less than the given key, or null. */
    public K lowerKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockRead();
        try {
            Node<K, V> n = findLessThan(key);
            return n == head ? null : n.key;
        } finally {
            unlockRead();
        }
    }

    /** Greatest key less than or equal to the given key, or null. */
    public K floorKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockRead();
        try {
            Node<K, V> n = findLessThan(key);
            Node<K, V> nx = n.next[0];
            if (nx != null && nx.key.compareTo(key) == 0) {
                return nx.key;
            }
            return n == head ? null : n.key;
        } finally {
            unlockRead();
        }
    }

    /** Least key greater than or equal to the given key, or null. */
    public K ceilingKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockRead();
        try {
            Node<K, V> n = findLessThan(key).next[0];
            return n == null ? null : n.key;
        } finally {
            unlockRead();
        }
    }

    /** Least key strictly greater than the given key, or null. */
    public K higherKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        lockRead();
        try {
            Node<K, V> n = findLessThan(key).next[0];
            if (n != null && n.key.compareTo(key) == 0) {
                n = n.next[0];
            }
            return n == null ? null : n.key;
        } finally {
            unlockRead();
        }
    }
}
