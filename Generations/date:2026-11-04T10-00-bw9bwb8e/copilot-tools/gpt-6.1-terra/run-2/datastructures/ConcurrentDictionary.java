/**
 * A thread-safe, dynamically sized dictionary.
 *
 * Keys and values must not be null. Keys must obey the usual equals/hashCode
 * contract and must not change in a way that affects equality while stored.
 * Operations do not wait for the dictionary to become nonempty or nonfull;
 * they may wait to acquire the internal monitor.
 *
 * A null return from get, put, or remove means that the key was not present.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Object[] buckets = new Object[INITIAL_CAPACITY];
    private int size;

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

    @SuppressWarnings("unchecked")
    private Node<K, V> bucketAt(Object[] table, int index) {
        return (Node<K, V>) table[index];
    }

    private int hash(K key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    /**
     * Associates the key with the value and returns its previous value,
     * or null if the key was absent.
     */
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException("Keys and values must not be null");
        }

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);

            for (Node<K, V> node = bucketAt(buckets, index);
                 node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary is too large");
            }

            Node<K, V> newNode = new Node<K, V>(hash, key, value, null);
            if (buckets.length < MAX_CAPACITY
                    && size >= buckets.length - (buckets.length >>> 2)) {
                resize();
                index = hash & (buckets.length - 1);
            }

            newNode.next = bucketAt(buckets, index);
            buckets[index] = newNode;
            size++;
            return null;
        }
    }

    /** Returns the value associated with the key, or null if absent. */
    public V get(K key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);
            for (Node<K, V> node = bucketAt(buckets, index);
                 node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    /** Returns whether the key is present. */
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /** Removes the key and returns its previous value, or null if absent. */
    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException("Key must not be null");
        }

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (buckets.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = bucketAt(buckets, index);

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
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

    /** Returns the number of entries. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns whether the dictionary has no entries. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all entries. */
    public void clear() {
        synchronized (lock) {
            buckets = new Object[INITIAL_CAPACITY];
            size = 0;
        }
    }

    private void resize() {
        Object[] newBuckets = new Object[buckets.length << 1];
        for (int i = 0; i < buckets.length; i++) {
            Node<K, V> node = bucketAt(buckets, i);
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newBuckets.length - 1);
                node.next = bucketAt(newBuckets, index);
                newBuckets[index] = node;
                node = next;
            }
        }
        buckets = newBuckets;
    }
}
