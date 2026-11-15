@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;

        Node(K key, V value, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final Node<K, V>[] buckets;
    private int size;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        buckets = (Node<K, V>[]) new Node[capacity];
    }

    public V get(K key) {
        requireKey(key);
        synchronized (lock) {
            Node<K, V> node = findNode(key, bucketIndex(key));
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (lock) {
            return findNode(key, bucketIndex(key)) != null;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        synchronized (lock) {
            int index = bucketIndex(key);
            Node<K, V> node = findNode(key, index);
            if (node != null) {
                V previous = node.value;
                node.value = value;
                return previous;
            }

            buckets[index] = new Node<K, V>(key, value, buckets[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (lock) {
            int index = bucketIndex(key);
            Node<K, V> previous = null;
            Node<K, V> node = buckets[index];

            while (node != null) {
                if (key.equals(node.key)) {
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

    private Node<K, V> findNode(K key, int index) {
        Node<K, V> node = buckets[index];
        while (node != null) {
            if (key.equals(node.key)) {
                return node;
            }
            node = node.next;
        }
        return null;
    }

    private int bucketIndex(K key) {
        return (key.hashCode() & 0x7fffffff) % buckets.length;
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("Null keys are not supported");
        }
    }

    private void requireValue(V value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }
}
