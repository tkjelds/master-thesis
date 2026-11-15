@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_INITIAL_CAPACITY = 16;

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

    private final Object mutex = new Object();
    private Node<K, V>[] buckets;
    private int size;
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        buckets = (Node<K, V>[]) new Node[initialCapacity];
        threshold = thresholdFor(initialCapacity);
    }

    public V put(K key, V value) {
        int hash = hashOf(key);
        synchronized (mutex) {
            int index = indexFor(hash, buckets.length);
            for (Node<K, V> node = buckets[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            buckets[index] = new Node<K, V>(hash, key, value, buckets[index]);
            size++;
            if (size > threshold) {
                resize();
            }
            return null;
        }
    }

    public V get(K key) {
        int hash = hashOf(key);
        synchronized (mutex) {
            for (Node<K, V> node = buckets[indexFor(hash, buckets.length)];
                 node != null;
                 node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        int hash = hashOf(key);
        synchronized (mutex) {
            for (Node<K, V> node = buckets[indexFor(hash, buckets.length)];
                 node != null;
                 node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        int hash = hashOf(key);
        synchronized (mutex) {
            int index = indexFor(hash, buckets.length);
            Node<K, V> previous = null;
            Node<K, V> node = buckets[index];

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
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (mutex) {
            buckets = (Node<K, V>[]) new Node[buckets.length];
            size = 0;
        }
    }

    private void resize() {
        if (buckets.length > Integer.MAX_VALUE / 2) {
            threshold = Integer.MAX_VALUE;
            return;
        }

        int newCapacity = buckets.length * 2;
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
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return (hash & 0x7fffffff) % capacity;
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static int thresholdFor(int capacity) {
        int result = capacity - (capacity >>> 2);
        return result == 0 ? 1 : result;
    }
}
