@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
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
    private Node<K, V>[] buckets;
    private int size;
    private int threshold;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }
        buckets = (Node<K, V>[]) new Node[initialCapacity];
        threshold = thresholdFor(initialCapacity);
    }

    public V put(K key, V value) {
        int hash = hashOf(key);
        synchronized (lock) {
            int index = indexFor(hash, buckets.length);
            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            if (size >= threshold && buckets.length < MAX_CAPACITY) {
                resize();
                index = indexFor(hash, buckets.length);
            }

            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        int hash = hashOf(key);
        synchronized (lock) {
            for (Node<K, V> node = buckets[indexFor(hash, buckets.length)];
                 node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        int hash = hashOf(key);
        synchronized (lock) {
            for (Node<K, V> node = buckets[indexFor(hash, buckets.length)];
                 node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        int hash = hashOf(key);
        synchronized (lock) {
            int index = indexFor(hash, buckets.length);
            Node<K, V> previous = null;
            Node<K, V> node = buckets[index];

            while (node != null) {
                if (node.hash == hash && keysEqual(node.key, key)) {
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

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (lock) {
            buckets = (Node<K, V>[]) new Node[buckets.length];
            size = 0;
        }
    }

    private void resize() {
        int newCapacity = buckets.length >= MAX_CAPACITY / 2
                ? MAX_CAPACITY
                : buckets.length * 2;
        if (newCapacity == buckets.length) {
            return;
        }

        @SuppressWarnings("unchecked")
        Node<K, V>[] newBuckets = (Node<K, V>[]) new Node[newCapacity];

        for (Node<K, V> head : buckets) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newCapacity);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }

        buckets = newBuckets;
        threshold = thresholdFor(newCapacity);
    }

    private static int hashOf(Object key) {
        int hash = key == null ? 0 : key.hashCode();
        return (hash ^ (hash >>> 16)) & 0x7fffffff;
    }

    private static int indexFor(int hash, int capacity) {
        return hash % capacity;
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static int thresholdFor(int capacity) {
        return Math.max(1, capacity - capacity / 4);
    }
}
