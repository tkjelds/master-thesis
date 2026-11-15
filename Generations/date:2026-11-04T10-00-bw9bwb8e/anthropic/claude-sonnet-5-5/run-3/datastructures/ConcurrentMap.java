// Thread-safe concurrent hash map using lock striping.
//
// Design:
//  - The key space is partitioned into a fixed number of segments. Each segment
//    has its own intrinsic monitor and its own hash table, so operations on
//    different segments proceed in parallel.
//  - Every access to a segment's mutable state happens inside a synchronized
//    block on that segment, which guarantees mutual exclusion and visibility.
//  - Policy for operations that cannot complete immediately: no operation ever
//    blocks waiting for a condition. Every operation completes right away and
//    reports its outcome through its return value (e.g. null or false when the
//    key is absent). Therefore wait/notify is not needed.
//  - Null keys and null values are rejected with NullPointerException.
//  - Whole-map operations (size, isEmpty, clear, containsValue) acquire all
//    segment locks in a fixed ascending order, so they are atomic with respect
//    to every other operation and cannot deadlock.
//  - computeIfAbsent runs the user function while holding one segment lock. The
//    function must be short and must not access this map.
@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}


    /** Function used by computeIfAbsent. */
    public interface MappingFunction<K, V> {
        V apply(K key);
    }

    private static final int SEGMENT_COUNT = 16; // power of two
    private static final int INITIAL_SEGMENT_CAPACITY = 8; // power of two
    private static final int MAX_SEGMENT_CAPACITY = 1 << 28;

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
        private Node<K, V>[] table;
        private int count;

        @SuppressWarnings("unchecked")
        Segment() {
            table = (Node<K, V>[]) new Node[INITIAL_SEGMENT_CAPACITY];
            count = 0;
        }

        V get(Object key, int hash) {
            synchronized (this) {
                Node<K, V> e = table[hash & (table.length - 1)];
                while (e != null) {
                    if (e.hash == hash && e.key.equals(key)) {
                        return e.value;
                    }
                    e = e.next;
                }
                return null;
            }
        }

        boolean containsKey(Object key, int hash) {
            return get(key, hash) != null;
        }

        V put(K key, int hash, V value, boolean onlyIfAbsent) {
            synchronized (this) {
                int idx = hash & (table.length - 1);
                Node<K, V> e = table[idx];
                while (e != null) {
                    if (e.hash == hash && e.key.equals(key)) {
                        V old = e.value;
                        if (!onlyIfAbsent) {
                            e.value = value;
                        }
                        return old;
                    }
                    e = e.next;
                }
                table[idx] = new Node<K, V>(key, hash, value, table[idx]);
                count++;
                if (count > (table.length >> 1) + (table.length >> 2)) {
                    resize();
                }
                return null;
            }
        }

        V computeIfAbsent(K key, int hash, MappingFunction<? super K, ? extends V> fn) {
            synchronized (this) {
                V existing = get(key, hash);
                if (existing != null) {
                    return existing;
                }
                V created = fn.apply(key);
                if (created == null) {
                    return null;
                }
                put(key, hash, created, true);
                return created;
            }
        }

        V replace(Object key, int hash, V newValue) {
            synchronized (this) {
                Node<K, V> e = table[hash & (table.length - 1)];
                while (e != null) {
                    if (e.hash == hash && e.key.equals(key)) {
                        V old = e.value;
                        e.value = newValue;
                        return old;
                    }
                    e = e.next;
                }
                return null;
            }
        }

        boolean replace(Object key, int hash, V expected, V newValue) {
            synchronized (this) {
                Node<K, V> e = table[hash & (table.length - 1)];
                while (e != null) {
                    if (e.hash == hash && e.key.equals(key)) {
                        if (e.value.equals(expected)) {
                            e.value = newValue;
                            return true;
                        }
                        return false;
                    }
                    e = e.next;
                }
                return false;
            }
        }

        // If expected is null, removes unconditionally; otherwise only if value matches.
        V remove(Object key, int hash, Object expected) {
            synchronized (this) {
                int idx = hash & (table.length - 1);
                Node<K, V> prev = null;
                Node<K, V> e = table[idx];
                while (e != null) {
                    if (e.hash == hash && e.key.equals(key)) {
                        if (expected != null && !e.value.equals(expected)) {
                            return null;
                        }
                        if (prev == null) {
                            table[idx] = e.next;
                        } else {
                            prev.next = e.next;
                        }
                        count--;
                        return e.value;
                    }
                    prev = e;
                    e = e.next;
                }
                return null;
            }
        }

        int size() {
            synchronized (this) {
                return count;
            }
        }

        boolean containsValue(Object value) {
            synchronized (this) {
                for (int i = 0; i < table.length; i++) {
                    Node<K, V> e = table[i];
                    while (e != null) {
                        if (e.value.equals(value)) {
                            return true;
                        }
                        e = e.next;
                    }
                }
                return false;
            }
        }

        @SuppressWarnings("unchecked")
        void clear() {
            synchronized (this) {
                table = (Node<K, V>[]) new Node[INITIAL_SEGMENT_CAPACITY];
                count = 0;
            }
        }

        // Must be called while holding this segment's monitor.
        @SuppressWarnings("unchecked")
        private void resize() {
            int oldCap = table.length;
            if (oldCap >= MAX_SEGMENT_CAPACITY) {
                return;
            }
            int newCap = oldCap << 1;
            Node<K, V>[] newTable = (Node<K, V>[]) new Node[newCap];
            for (int i = 0; i < oldCap; i++) {
                Node<K, V> e = table[i];
                while (e != null) {
                    Node<K, V> next = e.next;
                    int idx = e.hash & (newCap - 1);
                    e.next = newTable[idx];
                    newTable[idx] = e;
                    e = next;
                }
            }
            table = newTable;
        }
    }

    private final Segment<K, V>[] segments;

    @SuppressWarnings("unchecked")
    public ConcurrentMap() {
        segments = (Segment<K, V>[]) new Segment[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment<K, V>();
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[(hash >>> 24) & (SEGMENT_COUNT - 1)];
    }

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }

    /** Associates value with key; returns the previous value or null. */
    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        int h = hash(key);
        return segmentFor(h).put(key, h, value, false);
    }

    /** Puts only if key is absent; returns the existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        int h = hash(key);
        return segmentFor(h).put(key, h, value, true);
    }

    /** Returns the value mapped to key, or null if absent. */
    public V get(Object key) {
        requireNonNull(key);
        int h = hash(key);
        return segmentFor(h).get(key, h);
    }

    public V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v == null ? defaultValue : v;
    }

    public boolean containsKey(Object key) {
        requireNonNull(key);
        int h = hash(key);
        return segmentFor(h).containsKey(key, h);
    }

    /** Atomically computes and inserts a value if key is absent. */
    public V computeIfAbsent(K key, MappingFunction<? super K, ? extends V> fn) {
        requireNonNull(key);
        requireNonNull(fn);
        int h = hash(key);
        return segmentFor(h).computeIfAbsent(key, h, fn);
    }

    /** Replaces the value only if key is present; returns old value or null. */
    public V replace(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        int h = hash(key);
        return segmentFor(h).replace(key, h, value);
    }

    /** Replaces the value only if currently mapped to expected. */
    public boolean replace(K key, V expected, V value) {
        requireNonNull(key);
        requireNonNull(expected);
        requireNonNull(value);
        int h = hash(key);
        return segmentFor(h).replace(key, h, expected, value);
    }

    /** Removes the mapping; returns the removed value or null. */
    public V remove(Object key) {
        requireNonNull(key);
        int h = hash(key);
        return segmentFor(h).remove(key, h, null);
    }

    /** Removes the mapping only if currently mapped to value. */
    public boolean remove(Object key, Object value) {
        requireNonNull(key);
        requireNonNull(value);
        int h = hash(key);
        return segmentFor(h).remove(key, h, value) != null;
    }

    /** Atomic snapshot size. */
    public int size() {
        return sizeLocked(0);
    }

    public boolean isEmpty() {
        return sizeLocked(0) == 0;
    }

    public boolean containsValue(Object value) {
        requireNonNull(value);
        return containsValueLocked(0, value);
    }

    /** Atomically removes all mappings. */
    public void clear() {
        clearLocked(0);
    }

    private int sizeLocked(int i) {
        if (i == segments.length) {
            int total = 0;
            for (int j = 0; j < segments.length; j++) {
                total += segments[j].size();
            }
            return total;
        }
        synchronized (segments[i]) {
            return sizeLocked(i + 1);
        }
    }

    private boolean containsValueLocked(int i, Object value) {
        if (i == segments.length) {
            for (int j = 0; j < segments.length; j++) {
                if (segments[j].containsValue(value)) {
                    return true;
                }
            }
            return false;
        }
        synchronized (segments[i]) {
            return containsValueLocked(i + 1, value);
        }
    }

    private void clearLocked(int i) {
        if (i == segments.length) {
            for (int j = 0; j < segments.length; j++) {
                segments[j].clear();
            }
            return;
        }
        synchronized (segments[i]) {
            clearLocked(i + 1);
        }
    }
}
