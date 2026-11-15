import java.lang.Runnable;

/**
 * A thread-safe hash map implemented with lock striping.
 *
 * Design:
 *  - The key space is partitioned into a fixed number of segments. Each segment
 *    has its own intrinsic monitor and its own resizable bucket table, so
 *    operations on different segments proceed in parallel.
 *  - All reads and writes of a segment's state happen inside synchronized
 *    blocks on that segment, which guarantees atomicity and visibility.
 *  - size(), isEmpty() and clear() acquire all segment monitors in ascending
 *    index order (a fixed order, so there is no deadlock) and therefore operate
 *    on an atomic snapshot of the whole map.
 *
 * Policy for operations that cannot complete immediately:
 *  - The map is unbounded, so no operation ever needs to wait for space.
 *  - Lookups on missing keys do not block; they return null / false.
 *  - Hence no operation blocks, and no wait/notify is required.
 *
 * Null keys and null values are not permitted (NullPointerException).
 */
@StripedConcurrentMap.ThreadSafe
public class StripedConcurrentMap<K, V> {
    @interface ThreadSafe {}


    /** Function used by computeIfAbsent (defined locally; no library types). */
    public interface MappingFunction<K, V> {
        V apply(K key);
    }

    private static final int SEGMENT_BITS = 4;
    private static final int SEGMENT_COUNT = 1 << SEGMENT_BITS;
    private static final int SEGMENT_MASK = SEGMENT_COUNT - 1;
    private static final int INITIAL_TABLE_SIZE = 8;

    private static final class Node<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private static final class Segment<K, V> {
        // All fields guarded by the monitor of this Segment object.
        Node<K, V>[] table;
        int count;

        @SuppressWarnings("unchecked")
        Segment() {
            table = (Node<K, V>[]) new Node[INITIAL_TABLE_SIZE];
            count = 0;
        }
    }

    // Array reference is final and elements are assigned in the constructor
    // before the object is published (final field semantics).
    private final Segment<K, V>[] segments;

    @SuppressWarnings("unchecked")
    public StripedConcurrentMap() {
        segments = (Segment<K, V>[]) new Segment[SEGMENT_COUNT];
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment<K, V>();
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int bucketIndex(int hash, int tableLength) {
        return (hash >>> SEGMENT_BITS) & (tableLength - 1);
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[hash & SEGMENT_MASK];
    }

    // Must be called while holding the segment's monitor.
    private Node<K, V> findNode(Segment<K, V> seg, int hash, Object key) {
        Node<K, V> n = seg.table[bucketIndex(hash, seg.table.length)];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    // Must be called while holding the segment's monitor.
    private void insertNew(Segment<K, V> seg, int hash, K key, V value) {
        if (seg.count + 1 > (seg.table.length * 3) / 4) {
            resize(seg);
        }
        int idx = bucketIndex(hash, seg.table.length);
        seg.table[idx] = new Node<K, V>(hash, key, value, seg.table[idx]);
        seg.count++;
    }

    // Must be called while holding the segment's monitor.
    @SuppressWarnings("unchecked")
    private void resize(Segment<K, V> seg) {
        Node<K, V>[] old = seg.table;
        Node<K, V>[] fresh = (Node<K, V>[]) new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node<K, V> n = old[i];
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = bucketIndex(n.hash, fresh.length);
                n.next = fresh[idx];
                fresh[idx] = n;
                n = next;
            }
        }
        seg.table = fresh;
    }

    // Must be called while holding the segment's monitor.
    private Node<K, V> unlink(Segment<K, V> seg, int hash, Object key) {
        int idx = bucketIndex(hash, seg.table.length);
        Node<K, V> prev = null;
        Node<K, V> n = seg.table[idx];
        while (n != null) {
            if (n.hash == hash && (n.key == key || n.key.equals(key))) {
                if (prev == null) {
                    seg.table[idx] = n.next;
                } else {
                    prev.next = n.next;
                }
                seg.count--;
                return n;
            }
            prev = n;
            n = n.next;
        }
        return null;
    }

    /** Returns the value mapped to key, or null if absent. */
    public V get(Object key) {
        if (key == null) throw new NullPointerException("key");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            return n == null ? null : n.value;
        }
    }

    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    /** Associates value with key; returns the previous value or null. */
    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n != null) {
                V old = n.value;
                n.value = value;
                return old;
            }
            insertNew(seg, h, key, value);
            return null;
        }
    }

    /** Atomically puts only if absent; returns existing value or null if inserted. */
    public V putIfAbsent(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n != null) {
                return n.value;
            }
            insertNew(seg, h, key, value);
            return null;
        }
    }

    /**
     * Atomically computes and stores a value if key is absent. The function is
     * invoked while holding the segment lock, so it must be short and must not
     * access this map. Returns the current (existing or computed) value, or null
     * if the function returned null (nothing stored).
     */
    public V computeIfAbsent(K key, MappingFunction<? super K, ? extends V> fn) {
        if (key == null) throw new NullPointerException("key");
        if (fn == null) throw new NullPointerException("fn");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n != null) {
                return n.value;
            }
            V v = fn.apply(key);
            if (v != null) {
                insertNew(seg, h, key, v);
            }
            return v;
        }
    }

    /** Replaces the value only if key is present; returns the old value or null. */
    public V replace(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n == null) return null;
            V old = n.value;
            n.value = value;
            return old;
        }
    }

    /** Atomically replaces oldValue with newValue if currently mapped to oldValue. */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null) throw new NullPointerException("key");
        if (oldValue == null) throw new NullPointerException("oldValue");
        if (newValue == null) throw new NullPointerException("newValue");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n != null && n.value.equals(oldValue)) {
                n.value = newValue;
                return true;
            }
            return false;
        }
    }

    /** Removes the mapping for key; returns the removed value or null. */
    public V remove(Object key) {
        if (key == null) throw new NullPointerException("key");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = unlink(seg, h, key);
            return n == null ? null : n.value;
        }
    }

    /** Removes the mapping only if currently mapped to value. */
    public boolean remove(Object key, Object value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        int h = hash(key);
        Segment<K, V> seg = segmentFor(h);
        synchronized (seg) {
            Node<K, V> n = findNode(seg, h, key);
            if (n != null && n.value.equals(value)) {
                unlink(seg, h, key);
                return true;
            }
            return false;
        }
    }

    /** Atomic snapshot of the number of mappings. */
    public int size() {
        return sizeFrom(0);
    }

    private int sizeFrom(int i) {
        Segment<K, V> seg = segments[i];
        synchronized (seg) {
            int s = seg.count;
            if (i + 1 < SEGMENT_COUNT) {
                s += sizeFrom(i + 1);
            }
            return s;
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
        Segment<K, V> seg = segments[i];
        synchronized (seg) {
            if (i + 1 < SEGMENT_COUNT) {
                clearFrom(i + 1);
            }
            seg.table = (Node<K, V>[]) new Node[INITIAL_TABLE_SIZE];
            seg.count = 0;
        }
    }

    // Simple demonstration.
    public static void main(String[] args) throws InterruptedException {
        final StripedConcurrentMap<Integer, Integer> map = new StripedConcurrentMap<Integer, Integer>();
        final int threads = 8;
        final int perThread = 5000;
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            ts[t] = new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < perThread; i++) {
                        map.put(id * perThread + i, i);
                    }
                    // Lock-free-style counter via CAS-like replace loop.
                    map.putIfAbsent(-1, 0);
                    for (int i = 0; i < 1000; i++) {
                        while (true) {
                            Integer cur = map.get(-1);
                            if (map.replace(-1, cur, cur + 1)) break;
                        }
                    }
                }
            });
            ts[t].start();
        }
        for (int t = 0; t < threads; t++) {
            ts[t].join();
        }
        System.out.println("size=" + map.size() + " (expected " + (threads * perThread + 1) + ")");
        System.out.println("counter=" + map.get(-1) + " (expected " + (threads * 1000) + ")");
    }
}
