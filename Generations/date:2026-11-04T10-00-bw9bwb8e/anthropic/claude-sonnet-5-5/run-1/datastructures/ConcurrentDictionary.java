@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}


    // Policy:
    //  - Non-blocking operations (get, put, remove, ...) never wait. They return null or false
    //    when there is nothing to return or the condition is not met.
    //  - awaitGet(key) blocks until the key is present, using wait/notifyAll on the segment
    //    monitor, and throws InterruptedException if interrupted.
    //  - Null keys and values are rejected with NullPointerException.
    //
    // Implementation: lock striping. The key space is split into a fixed number of segments.
    // Each segment has its own monitor and its own resizable hash table, and all access to a
    // segment's state happens inside synchronized(segment). Operations spanning segments
    // (size, clear) acquire all segment locks in ascending index order, so they cannot
    // deadlock and they see an atomic snapshot.

    private static final int SEGMENT_COUNT = 16; // must be a power of two
    private static final int INITIAL_BUCKETS = 8; // must be a power of two

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
        // All fields are guarded by the monitor of this Segment object.
        Node<K, V>[] table;
        int count;

        @SuppressWarnings("unchecked")
        Segment() {
            table = (Node<K, V>[]) new Node[INITIAL_BUCKETS];
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

    private static int spread(int h) {
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }

    private Segment<K, V> segmentFor(int hash) {
        return segments[(hash >>> 16) & (SEGMENT_COUNT - 1)];
    }

    private static void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void checkValue(Object value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
    }

    // Must be called with the segment lock held.
    private Node<K, V> find(Segment<K, V> seg, Object key, int hash) {
        Node<K, V>[] tab = seg.table;
        Node<K, V> n = tab[hash & (tab.length - 1)];
        while (n != null) {
            if (n.hash == hash && n.key.equals(key)) {
                return n;
            }
            n = n.next;
        }
        return null;
    }

    // Must be called with the segment lock held. Key must be absent.
    private void insertNew(Segment<K, V> seg, K key, int hash, V value) {
        if (seg.count + 1 > seg.table.length - (seg.table.length >> 2)) {
            resize(seg);
        }
        Node<K, V>[] tab = seg.table;
        int idx = hash & (tab.length - 1);
        tab[idx] = new Node<K, V>(key, hash, value, tab[idx]);
        seg.count++;
        seg.notifyAll(); // wake threads blocked in awaitGet
    }

    @SuppressWarnings("unchecked")
    private void resize(Segment<K, V> seg) {
        Node<K, V>[] old = seg.table;
        Node<K, V>[] nt = (Node<K, V>[]) new Node[old.length * 2];
        for (int i = 0; i < old.length; i++) {
            Node<K, V> n = old[i];
            while (n != null) {
                Node<K, V> next = n.next;
                int idx = n.hash & (nt.length - 1);
                n.next = nt[idx];
                nt[idx] = n;
                n = next;
            }
        }
        seg.table = nt;
    }

    // Must be called with the segment lock held.
    private Node<K, V> unlink(Segment<K, V> seg, Object key, int hash) {
        Node<K, V>[] tab = seg.table;
        int idx = hash & (tab.length - 1);
        Node<K, V> prev = null;
        Node<K, V> n = tab[idx];
        while (n != null) {
            if (n.hash == hash && n.key.equals(key)) {
                if (prev == null) {
                    tab[idx] = n.next;
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

    /** Associates value with key. Returns the previous value, or null if there was none. */
    public V put(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            if (n != null) {
                V old = n.value;
                n.value = value;
                return old;
            }
            insertNew(seg, key, hash, value);
            return null;
        }
    }

    /** Inserts only if absent. Returns the existing value, or null if the mapping was inserted. */
    public V putIfAbsent(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            if (n != null) {
                return n.value;
            }
            insertNew(seg, key, hash, value);
            return null;
        }
    }

    /** Returns the value for key, or null if absent. Never blocks waiting for the key. */
    public V get(Object key) {
        checkKey(key);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            return n == null ? null : n.value;
        }
    }

    public V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v == null ? defaultValue : v;
    }

    /** Blocks until a mapping for key exists, then returns its value. */
    public V awaitGet(K key) throws InterruptedException {
        checkKey(key);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            while (n == null) {
                seg.wait();
                n = find(seg, key, hash);
            }
            return n.value;
        }
    }

    public boolean containsKey(Object key) {
        checkKey(key);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            return find(seg, key, hash) != null;
        }
    }

    /** Removes the mapping for key. Returns the removed value, or null if absent. */
    public V remove(Object key) {
        checkKey(key);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = unlink(seg, key, hash);
            return n == null ? null : n.value;
        }
    }

    /** Removes the mapping only if currently mapped to the given value. */
    public boolean remove(Object key, Object value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            if (n != null && n.value.equals(value)) {
                unlink(seg, key, hash);
                return true;
            }
            return false;
        }
    }

    /** Replaces the value only if key is present. Returns the old value or null. */
    public V replace(K key, V value) {
        checkKey(key);
        checkValue(value);
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
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
        int hash = spread(key.hashCode());
        Segment<K, V> seg = segmentFor(hash);
        synchronized (seg) {
            Node<K, V> n = find(seg, key, hash);
            if (n != null && n.value.equals(oldValue)) {
                n.value = newValue;
                return true;
            }
            return false;
        }
    }

    /** Returns an atomic snapshot count of mappings. */
    public int size() {
        return sizeFrom(0);
    }

    private int sizeFrom(int i) {
        if (i == segments.length) {
            return 0;
        }
        Segment<K, V> seg = segments[i];
        synchronized (seg) {
            return seg.count + sizeFrom(i + 1);
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
            seg.table = (Node<K, V>[]) new Node[INITIAL_BUCKETS];
            seg.count = 0;
            clearFrom(i + 1);
        }
    }
}
