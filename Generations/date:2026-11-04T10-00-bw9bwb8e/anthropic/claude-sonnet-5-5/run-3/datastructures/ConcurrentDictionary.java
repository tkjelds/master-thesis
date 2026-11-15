import java.lang.Object;

/**
 * A thread-safe dictionary (hash map) implemented with lock striping.
 *
 * Design:
 *  - The key space is partitioned into a fixed number of segments. Each segment
 *    has its own intrinsic monitor and its own separately chained hash table,
 *    so operations on different segments proceed in parallel.
 *  - Every access to a segment's mutable state (including reads) happens inside
 *    a synchronized block on that segment, which gives mutual exclusion and
 *    happens-before edges (no lost updates, no visibility problems).
 *  - Whole-dictionary operations (size, clear) acquire all segment locks in
 *    ascending index order, so they see an atomic snapshot and cannot deadlock.
 *
 * Policy for operations that cannot complete immediately:
 *  - put / putIfAbsent / remove / get / containsKey never block on a condition.
 *    The dictionary is unbounded, and get returns null if the key is absent.
 *  - take-style blocking lookups are provided: getBlocking waits (wait/notifyAll,
 *    guarded by a while loop) until the key is present; getBlocking(key, timeout)
 *    waits at most the given time and returns null on timeout.
 *  - Null keys and null values are rejected with NullPointerException.
 */
@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    private static final class Node<K, V> {
        final K key;
        final int hash;
        V value;
        Node<K, V> next;

        Node(K key, int hash, V value, Node<K, V> next) {
            this.key = key;
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Segment<K, V> {
        Node<K, V>[] table;
        int count;
        int waiters;

        @SuppressWarnings("unchecked")
        Segment(int capacity) {
            table = (Node<K, V>[]) new Node[capacity];
        }
    }

    private static final int DEFAULT_SEGMENTS = 16;
    private static final int INITIAL_SEGMENT_CAPACITY = 8;

    private final Segment<K, V>[] segments;
    private final int segmentMask;
    private final int segmentBits;

    public ConcurrentDictionary() {
        this(DEFAULT_SEGMENTS);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int concurrencyLevel) {
        if (concurrencyLevel < 1) {
            throw new IllegalArgumentException("concurrencyLevel must be >= 1");
        }
        int n = 1;
        int bits = 0;
        while (n < concurrencyLevel && n < (1 << 16)) {
            n <<= 1;
            bits++;
        }
        segments = (Segment<K, V>[]) new Segment[n];
        for (int i = 0; i < n; i++) {
            segments[i] = new Segment<K, V>(INITIAL_SEGMENT_CAPACITY);
        }
        segmentMask = n - 1;
        segmentBits = bits;
    }

    private static int spread(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[hash & segmentMask];
    }

    private int bucketIndex(int hash, int length) {
        return (hash >>> segmentBits) & (length - 1);
    }

    private Node<K, V> findNode(Segment<K, V> seg, Object key, int hash) {
        Node<K, V> n = seg.table[bucketIndex(hash, seg.table.length)];
        while (n != null) {
            if (n.hash == hash && n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    // Must be called while holding the segment's monitor.
    @SuppressWarnings("unchecked")
    private void resize(Segment<K, V> seg) {
        Node<K, V>[] old = seg.table;
        int newLen = old.length * 2;
        Node<K, V>[] fresh = (Node<K, V>[]) new Node[newLen];
        for (int i = 0; i < old.length; i++) {
            Node<K, V> n = old[i];
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = bucketIndex(n.hash, newLen);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        seg.table = fresh;
    }

    // Must be called while holding the segment's monitor.
    private void insertNew(Segment<K, V> seg, K key, int hash, V value) {
        if (seg.count + 1 > (seg.table.length * 3) / 4) {
            resize(seg);
        }
        int idx = bucketIndex(hash, seg.table.length);
        seg.table[idx] = new Node<K, V>(key, hash, value, seg.table[idx]);
        seg.count++;
        if (seg.waiters > 0) {
            seg.notifyAll();
        }
    }

    /** Associates value with key; returns the previous value or null. */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
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
        if (key == null || value == null) {
            throw new NullPointerException();
        }
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

    /** Returns the value mapped to key, or null if absent. */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            return n == null ? null : n.value;
        }
    }

    /** Returns the value for key, or defaultValue if absent. */
    public V getOrDefault(K key, V defaultValue) {
        V v = get(key);
        return v == null ? defaultValue : v;
    }

    /** Waits until key is present, then returns its value. */
    public V getBlocking(K key) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            while (n == null) {
                seg.waiters++;
                try {
                    seg.wait();
                } finally {
                    seg.waiters--;
                }
                n = findNode(seg, key, hash);
            }
            return n.value;
        }
    }

    /** Waits up to timeoutMillis for key to appear; returns null on timeout. */
    public V getBlocking(K key, long timeoutMillis) throws InterruptedException {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        long timeoutNanos = timeoutMillis <= 0 ? 0L : timeoutMillis * 1000000L;
        long start = System.nanoTime();
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            while (n == null) {
                long remaining = timeoutNanos - (System.nanoTime() - start);
                if (remaining <= 0) {
                    return null;
                }
                long millis = remaining / 1000000L;
                int nanos = (int) (remaining % 1000000L);
                if (millis == 0 && nanos == 0) {
                    nanos = 1;
                }
                seg.waiters++;
                try {
                    seg.wait(millis, nanos);
                } finally {
                    seg.waiters--;
                }
                n = findNode(seg, key, hash);
            }
            return n.value;
        }
    }

    /** Removes the mapping for key; returns the removed value or null. */
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            int idx = bucketIndex(hash, seg.table.length);
            Node<K, V> prev = null;
            Node<K, V> n = seg.table[idx];
            while (n != null) {
                if (n.hash == hash && n.key.equals(key)) {
                    if (prev == null) {
                        seg.table[idx] = n.next;
                    } else {
                        prev.next = n.next;
                    }
                    seg.count--;
                    return n.value;
                }
                prev = n;
                n = n.next;
            }
            return null;
        }
    }

    /** Removes the mapping only if currently mapped to the given value. */
    public boolean remove(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            int idx = bucketIndex(hash, seg.table.length);
            Node<K, V> prev = null;
            Node<K, V> n = seg.table[idx];
            while (n != null) {
                if (n.hash == hash && n.key.equals(key)) {
                    if (!n.value.equals(value)) {
                        return false;
                    }
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
    }

    /** Replaces the value only if key is currently mapped to oldValue. */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null) {
            throw new NullPointerException();
        }
        int hash = spread(key);
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, key, hash);
            if (n != null && n.value.equals(oldValue)) {
                n.value = newValue;
                return true;
            }
            return false;
        }
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** Atomic snapshot of the total number of mappings. */
    public int size() {
        return sumSizes(0);
    }

    private int sumSizes(int i) {
        if (i == segments.length) {
            return 0;
        }
        Segment<K, V> seg = segments[i];
        synchronized (seg) {
            return seg.count + sumSizes(i + 1);
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
            seg.table = (Node<K, V>[]) new Node[INITIAL_SEGMENT_CAPACITY];
            seg.count = 0;
            clearFrom(i + 1);
        }
    }
}
