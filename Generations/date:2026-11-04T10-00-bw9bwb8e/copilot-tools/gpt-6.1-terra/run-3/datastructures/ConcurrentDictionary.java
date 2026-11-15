@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
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
    private Node<?, ?>[] buckets = new Node<?, ?>[INITIAL_CAPACITY];
    private int size;
    private int resizeThreshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;

    /*
     * Null keys and values are permitted. A null result from get, put, or
     * remove may mean either absence or a stored null value; use containsKey
     * when that distinction is needed.
     *
     * Keys must retain stable hashCode and equals behavior while stored.
     * Their hashCode and equals methods must not mutate this dictionary.
     * Objects stored in or returned by the dictionary are not themselves
     * protected by the dictionary's lock.
     *
     * Operations do not wait for entries or capacity, although they may wait
     * to acquire the internal monitor.
     */

    public V get(K key) {
        synchronized (lock) {
            Node<K, V> node = findNode(key, hash(key));
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
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

            if (size == 2147483647) {
                throw new IllegalStateException("Dictionary is too large");
            }

            if (size >= resizeThreshold && buckets.length < MAX_CAPACITY) {
                resize();
            }

            int index = indexFor(hash, buckets.length);
            buckets[index] = new Node<K, V>(hash, key, value, nodeAt(buckets, index));
            size++;
            return null;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, buckets.length);
            Node<K, V> previous = null;
            Node<K, V> node = nodeAt(buckets, index);

            while (node != null) {
                if (matches(node, key, hash)) {
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
            buckets = new Node<?, ?>[INITIAL_CAPACITY];
            size = 0;
            resizeThreshold = INITIAL_CAPACITY - INITIAL_CAPACITY / 4;
        }
    }

    private int hash(K key) {
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private int indexFor(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    private boolean matches(Node<K, V> node, K key, int hash) {
        return node.hash == hash
                && (node.key == key || (key != null && key.equals(node.key)));
    }

    private Node<K, V> findNode(K key, int hash) {
        Node<K, V> node = nodeAt(buckets, indexFor(hash, buckets.length));
        while (node != null) {
            if (matches(node, key, hash)) {
                return node;
            }
            node = node.next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> nodeAt(Node<?, ?>[] array, int index) {
        return (Node<K, V>) array[index];
    }

    private void resize() {
        int newCapacity = buckets.length * 2;
        Node<?, ?>[] newBuckets = new Node<?, ?>[newCapacity];

        for (int i = 0; i < buckets.length; i++) {
            Node<K, V> node = nodeAt(buckets, i);
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newCapacity);
                node.next = nodeAt(newBuckets, index);
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        resizeThreshold = newCapacity - newCapacity / 4;
    }
}
