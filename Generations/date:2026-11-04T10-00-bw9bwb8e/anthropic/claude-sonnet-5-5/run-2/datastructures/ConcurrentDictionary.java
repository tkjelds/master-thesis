import java.io.Serializable;

/**
 * A thread-safe hash dictionary built on lock striping.
 *
 * Policies:
 *  - The dictionary is unbounded, so put-style operations never block.
 *  - Null keys and null values are rejected with NullPointerException.
 *  - get, remove, etc. return null or false immediately when the key is absent.
 *  - awaitGet(key) is the only blocking operation. It waits, using wait/notifyAll
 *    on the owning segment's monitor, until a value for the key is present.
 *  - The keyspace is partitioned into segments, and each segment is guarded by its
 *    own monitor. Whole-dictionary operations (size, clear) lock the segments in
 *    ascending index order, which avoids deadlock and gives an atomic snapshot.
 */
@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    private static final int SEGMENT_BITS = 4;
    private static final int SEGMENT_COUNT = 1 << SEGMENT_BITS;
    private static final int INITIAL_CAPACITY = 8;

    private static final class Node<K, V> {
        final K key;
        final int hash;
        V value;      // guarded by the owning segment's monitor
        Node<K, V> next; // guarded by the owning segment's monitor

        Node(K key, int hash, V value, Node<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Segment<K, V> {
        Node<K, V>[] table; // guarded by this
        int count;          // guarded by this

        @SuppressWarnings("unchecked")
        Segment() {
            table = (Node<K, V>[]) new Node[INITIAL_CAPACITY];
            count = 0;
        }
    }

    private final Segment<K, V>[] segments;

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary() {
        segments = (Segment<K, V>[]) new Segment[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment<K, V>();
        }
    }

    // ---------- helpers ----------

    private static int spread(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[hash & (SEGMENT_COUNT - 1)];
    }

    private static int bucketIndex(int hash, int length) {
        return (hash >>> SEGMENT_BITS) & (length - 1);
    }

    private static void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }
    }

    private static void checkValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value is null");
        }
    }

    // Must be called while holding seg's monitor.
    private Node<K, V> findNode(Segment<K, V> seg, Object key, int hash) {
        Node<K, V> n = seg.table[bucketIndex(hash, seg.table.length)];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    // Must be called while holding seg's monitor. Key must be absent.
    private void insertNew(Segment<K, V> seg, K key, int hash, V value) {
        if (seg.count + 1 > (seg.table.length * 3) / 4) {
            resize(seg);
        }
        int idx = bucketIndex(hash, seg.table.length);
        seg.table[idx] = new Node<K, V>(key, hash, value, seg.table[idx]);
        seg.count++;
        seg.notifyAll(); // wake threads blocked in awaitGet
    }

    // Must be called while holding seg's monitor.
    @SuppressWarnings("unchecked")
    private void resize(Segment<K, V> seg) {
        Node<K, V>[] old = seg.table;
        Node<K, V>[] nt = (Node<K, V>[]) new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node<K, V> n = old[i];
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = bucketIndex(n.hash, nt.length);
                n.next = nt[idx];
                nt[idx] = n;
                n = next;
            }
        }
        seg.table = nt;
    }

    // Must be called while holding seg's monitor.
    private boolean unlink(Segment<K, V> seg, Node<K, V> target) {
        int idx = bucketIndex(target.hash, seg.table.length);
        Node<K, V> prev = null;
        Node<K, V> n = seg.table[idx];
        while (n != null) {
            if (n == target) {
                if (prev == null) {
                    seg.table[idx] = n.next;
                } else {
                    prev.next = n.next;
                }
                seg.count--;
                return true;
            }
            prev = n;
            n = n.next;
        }
        return false;
    }

    // ---------- public API ----------

    /** Associates value with key; returns the previous value or null. */
    public V put(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n != null) {
                V old = n.value;
                n.value = value;
                return old;
            }
            insertNew(seg, key, hash, value);
            return null;
        }
    }

    /** Inserts only if absent; returns the existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n != null) {
                return n.value;
            }
            insertNew(seg, key, hash, value);
            return null;
        }
    }

    /** Returns the value for key or null if absent. Never blocks. */
    public V get(Object key) {
        checkKey(key);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            return n == null ? null : n.value;
        }
    }

    public V getOrDefault(Object key, V defaultValue) {
        checkKey(key);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            return n == null ? defaultValue : n.value;
        }
    }

    /** Blocks until a mapping for key exists, then returns its value. */
    public V awaitGet(K key) throws InterruptedException {
        checkKey(key);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            while (n == null) {
                seg.wait();
                n = findNode(seg, key, hash);
            }
            return n.value;
        }
    }

    public boolean containsKey(Object key) {
        checkKey(key);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            return findNode(seg, key, hash) != null;
        }
    }

    /** Removes the mapping for key; returns the removed value or null. */
    public V remove(Object key) {
        checkKey(key);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n == null) {
                return null;
            }
            V old = n.value;
            unlink(seg, n);
            return old;
        }
    }

    /** Removes the mapping only if it is currently mapped to value. */
    public boolean remove(Object key, Object value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n == null || !n.value.equals(value)) {
                return false;
            }
            unlink(seg, n);
            return true;
        }
    }

    /** Replaces the value only if key is present; returns previous value or null. */
    public V replace(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n == null) {
                return null;
            }
            V old = n.value;
            n.value = value;
            return old;
        }
    }

    /** Atomically replaces oldValue with newValue for key. */
    public boolean replace(K key, V oldValue, V newValue) {
        checkKey(key);
        checkValue(oldValue);
        checkValue(newValue);
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n == null || !n.value.equals(oldValue)) {
                return false;
            }
            n.value = newValue;
            return true;
        }
    }

    /** Atomic snapshot of the number of mappings. */
    public int size() {
        return sizeFrom(0, 0);
    }

    private int sizeFrom(int i, int acc) {
        if (i == segments.length) {
            return acc;
        }
        synchronized (segments[i]) {
            return sizeFrom(i + 1, acc + segments[i].count);
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Atomically removes all mappings. */
    public void clear() {
        clearFrom(0);
    }

    @SuppressWarnings("unchecked")
    private void clearFrom(int i) {
        if (i == segments.length) {
            return;
        }
        Segment<K, V> seg = segments[i];
        synchronized (seg) {
            seg.table = (Node<K, V>[]) new Node[INITIAL_CAPACITY];
            seg.count = 0;
            clearFrom(i + 1);
        }
    }
}
