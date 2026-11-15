/**
 * A thread-safe hash map supporting null keys and null values.
 *
 * Operations return immediately when a key is absent; they may block while
 * acquiring the map's monitor, but never wait for a key or available capacity.
 * A null return from get, put, or remove can mean either an absent mapping or
 * a mapping whose value is null; use containsKey to distinguish them.
 *
 * Keys must have stable hashCode and equals behavior while stored in the map.
 * Their hashCode and equals methods are called while the map's monitor is held.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

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

    private final Object lock = new Object();
    private Object[] buckets = new Object[INITIAL_CAPACITY];
    private int size;
    private int threshold = INITIAL_CAPACITY - (INITIAL_CAPACITY >>> 2);

    private int hash(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    private boolean keysEqual(Object key, Object storedKey) {
        return key == storedKey || (key != null && key.equals(storedKey));
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> bucketAt(int index) {
        return (Node<K, V>) buckets[index];
    }

    private Node<K, V> findNode(Object key, int hash) {
        int index = hash & (buckets.length - 1);
        for (Node<K, V> node = bucketAt(index); node != null; node = node.next) {
            if (node.hash == hash && keysEqual(key, node.key)) {
                return node;
            }
        }
        return null;
    }

    public V get(Object key) {
        synchronized (lock) {
            Node<K, V> node = findNode(key, hash(key));
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(Object key) {
        synchronized (lock) {
            return findNode(key, hash(key)) != null;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            Node<K, V> existing = findNode(key, hash);
            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Map is too large");
            }
            if (size >= threshold && buckets.length < MAX_CAPACITY) {
                resize();
            }

            int index = hash & (buckets.length - 1);
            buckets[index] = new Node<K, V>(hash, key, value, bucketAt(index));
            size++;
            return null;
        }
    }

    public V remove(Object key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = bucketAt(index);

            while (node != null) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    if (previous == null) {
                        buckets[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return node.value;
                }
                previous = node;
                node = node.next;
            }
            return null;
        }
    }

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

    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
        }
    }

    private void resize() {
        int newCapacity = buckets.length << 1;
        Object[] newBuckets = new Object[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Node<K, V> node = bucketAt(i);
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newCapacity - 1);
                @SuppressWarnings("unchecked")
                Node<K, V> head = (Node<K, V>) newBuckets[index];
                node.next = head;
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = newCapacity - (newCapacity >>> 2);
    }
}
