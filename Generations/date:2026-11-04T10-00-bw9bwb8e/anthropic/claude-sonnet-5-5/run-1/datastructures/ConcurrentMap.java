import java.lang.*;

/**
 * A thread-safe hash map built on lock striping.
 *
 * Design and policy:
 *  - The key space is partitioned into a fixed number of segments. Each segment
 *    has its own monitor and its own bucket table, so operations on different
 *    segments run in parallel.
 *  - All access to a segment's state (reads and writes) happens inside
 *    synchronized blocks on that segment, which gives mutual exclusion and
 *    happens-before visibility guarantees.
 *  - No operation blocks waiting for a condition. Operations that cannot
 *    "complete" in the usual sense (for example, get on a missing key) return
 *    null or false immediately. Therefore no wait/notify is needed, and there
 *    is no busy-waiting.
 *  - Null keys and null values are rejected with NullPointerException.
 *  - size() and clear() lock all segments in a fixed index order, so there is
 *    no deadlock and size() returns an atomic snapshot.
 *  - Functional callbacks (computeIfAbsent) run while holding the segment lock.
 *    They must be short and must not call back into the same map.
 */
@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}


    /** Function used by computeIfAbsent (self-defined, no library types). */
    public interface MappingFunction<K, V> {
        V apply(K key);
    }

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
        private int threshold;

        @SuppressWarnings("unchecked")
        Segment(int initialCapacity) {
            table = (Node<K, V>[]) new Node[initialCapacity];
            threshold = (initialCapacity * 3) / 4;
            if (threshold < 1) {
                threshold = 1;
            }
        }

        V get(Object key, int hash) {
            synchronized (this) {
                Node<K, V> n = table[hash & (table.length - 1)];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        return n.value;
                    }
                    n = n.next;
                }
                return null;
            }
        }

        boolean containsKey(Object key, int hash) {
            return get(key, hash) != null;
        }

        boolean containsValue(Object value) {
            synchronized (this) {
                for (int i = 0; i < table.length; i++) {
                    Node<K, V> n = table[i];
                    while (n != null) {
                        if (n.value.equals(value)) {
                            return true;
                        }
                        n = n.next;
                    }
                }
                return false;
            }
        }

        V put(K key, int hash, V value, boolean onlyIfAbsent) {
            synchronized (this) {
                int idx = hash & (table.length - 1);
                Node<K, V> n = table[idx];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        V old = n.value;
                        if (!onlyIfAbsent) {
                            n.value = value;
                        }
                        return old;
                    }
                    n = n.next;
                }
                table[idx] = new Node<K, V>(key, hash, value, table[idx]);
                count++;
                if (count > threshold) {
                    resize();
                }
                return null;
            }
        }

        V computeIfAbsent(K key, int hash, MappingFunction<K, V> fn) {
            synchronized (this) {
                int idx = hash & (table.length - 1);
                Node<K, V> n = table[idx];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        return n.value;
                    }
                    n = n.next;
                }
                V created = fn.apply(key);
                if (created == null) {
                    return null;
                }
                // Re-compute index in case the table state could have changed
                // (it cannot unless fn re-enters the map, but stay safe).
                idx = hash & (table.length - 1);
                table[idx] = new Node<K, V>(key, hash, created, table[idx]);
                count++;
                if (count > threshold) {
                    resize();
                }
                return created;
            }
        }

        V replace(K key, int hash, V value) {
            synchronized (this) {
                Node<K, V> n = table[hash & (table.length - 1)];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        V old = n.value;
                        n.value = value;
                        return old;
                    }
                    n = n.next;
                }
                return null;
            }
        }

        boolean replace(K key, int hash, V expected, V value) {
            synchronized (this) {
                Node<K, V> n = table[hash & (table.length - 1)];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        if (n.value.equals(expected)) {
                            n.value = value;
                            return true;
                        }
                        return false;
                    }
                    n = n.next;
                }
                return false;
            }
        }

        /** Removes the key; if expected != null, only when the value matches. */
        V remove(Object key, int hash, Object expected) {
            synchronized (this) {
                int idx = hash & (table.length - 1);
                Node<K, V> prev = null;
                Node<K, V> n = table[idx];
                while (n != null) {
                    if (n.hash == hash && n.key.equals(key)) {
                        if (expected != null && !n.value.equals(expected)) {
                            return null;
                        }
                        if (prev == null) {
                            table[idx] = n.next;
                        } else {
                            prev.next = n.next;
                        }
                        count--;
                        return n.value;
                    }
                    prev = n;
                    n = n.next;
                }
                return null;
            }
        }

        // Caller must hold the segment lock.
        int countLocked() {
            return count;
        }

        // Caller must hold the segment lock.
        void clearLocked() {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            count = 0;
        }

        // Caller must hold the segment lock.
        @SuppressWarnings("unchecked")
        private void resize() {
            Node<K, V>[] old = table;
            int newCap = old.length * 2;
            Node<K, V>[] nt = (Node<K, V>[]) new Node[newCap];
            for (int i = 0; i < old.length; i++) {
                Node<K, V> n = old[i];
                while (n != null) {
                    Node<K, V> next = n.next;
                    int idx = n.hash & (newCap - 1);
                    n.next = nt[idx];
                    nt[idx] = n;
                    n = next;
                }
            }
            table = nt;
            threshold = (newCap * 3) / 4;
        }
    }

    private static final int DEFAULT_SEGMENTS = 16;
    private static final int DEFAULT_SEGMENT_CAPACITY = 8;

    private final Segment<K, V>[] segments;
    private final int segmentMask;

    public ConcurrentMap() {
        this(DEFAULT_SEGMENTS, DEFAULT_SEGMENT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int segmentCount, int initialSegmentCapacity) {
        if (segmentCount < 1 || initialSegmentCapacity < 1) {
            throw new IllegalArgumentException("capacity and segment count must be positive");
        }
        int sc = 1;
        while (sc < segmentCount && sc < (1 << 16)) {
            sc <<= 1;
        }
        int cap = 1;
        while (cap < initialSegmentCapacity && cap < (1 << 20)) {
            cap <<= 1;
        }
        segments = (Segment<K, V>[]) new Segment[sc];
        for (int i = 0; i < sc; i++) {
            segments[i] = new Segment<K, V>(cap);
        }
        segmentMask = sc - 1;
    }

    private static int spread(Object key) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[(hash >>> 16) & segmentMask];
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    /** Returns the value mapped to key, or null if absent. */
    public V get(Object key) {
        requireKey(key);
        int h = spread(key);
        return segmentFor(h).get(key, h);
    }

    public boolean containsKey(Object key) {
        requireKey(key);
        int h = spread(key);
        return segmentFor(h).containsKey(key, h);
    }

    /** Weakly consistent: segments are inspected one at a time. */
    public boolean containsValue(Object value) {
        requireValue(value);
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].containsValue(value)) {
                return true;
            }
        }
        return false;
    }

    /** Associates value with key; returns the previous value or null. */
    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        int h = spread(key);
        return segmentFor(h).put(key, h, value, false);
    }

    /** Inserts only if absent; returns the existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        requireKey(key);
        requireValue(value);
        int h = spread(key);
        return segmentFor(h).put(key, h, value, true);
    }

    /**
     * If key is absent, computes a value with fn (atomically with respect to
     * this key's segment) and stores it. Returns the current mapped value, or
     * null if fn returned null and nothing was stored.
     */
    public V computeIfAbsent(K key, MappingFunction<K, V> fn) {
        requireKey(key);
        if (fn == null) {
            throw new NullPointerException("fn");
        }
        int h = spread(key);
        return segmentFor(h).computeIfAbsent(key, h, fn);
    }

    /** Replaces the value only if key is present; returns old value or null. */
    public V replace(K key, V value) {
        requireKey(key);
        requireValue(value);
        int h = spread(key);
        return segmentFor(h).replace(key, h, value);
    }

    /** Replaces the value only if currently mapped to expected. */
    public boolean replace(K key, V expected, V value) {
        requireKey(key);
        requireValue(expected);
        requireValue(value);
        int h = spread(key);
        return segmentFor(h).replace(key, h, expected, value);
    }

    /** Removes the mapping; returns the removed value or null. */
    public V remove(Object key) {
        requireKey(key);
        int h = spread(key);
        return segmentFor(h).remove(key, h, null);
    }

    /** Removes the mapping only if currently mapped to value. */
    public boolean remove(Object key, Object value) {
        requireKey(key);
        requireValue(value);
        int h = spread(key);
        return segmentFor(h).remove(key, h, value) != null;
    }

    /** Atomic snapshot of the number of mappings. */
    public int size() {
        return sizeFrom(0);
    }

    private int sizeFrom(int index) {
        if (index == segments.length) {
            return 0;
        }
        Segment<K, V> s = segments[index];
        synchronized (s) {
            return s.countLocked() + sizeFrom(index + 1);
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** Atomically removes all mappings. */
    public void clear() {
        clearFrom(0);
    }

    private void clearFrom(int index) {
        if (index == segments.length) {
            return;
        }
        Segment<K, V> s = segments[index];
        synchronized (s) {
            s.clearLocked();
            clearFrom(index + 1);
        }
    }
}
