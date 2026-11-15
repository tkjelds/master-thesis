/**
 * A dynamically resized, thread-safe dictionary.
 * Null keys and values are not permitted. put and remove return the previous
 * value, or null if the key was not present.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

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
    private volatile Node<K, V>[] buckets;
    private int size;

    public ConcurrentDictionary() {
        this(16);
    }

    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }
        buckets = newTable(initialCapacity);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V>[] table = buckets;
            int index = indexFor(hash, table.length);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            if (size >= threshold(table.length) && table.length < MAX_CAPACITY) {
                resize();
                table = buckets;
                index = indexFor(hash, table.length);
            }

            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V>[] table = buckets;
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                 node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V>[] table = buckets;
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                 node != null; node = node.next) {
                if (node.hash == hash && node.key.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = hash(key);
            Node<K, V>[] table = buckets;
            int index = indexFor(hash, table.length);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == hash && node.key.equals(key)) {
                    if (previous == null) {
                        table[index] = node.next;
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
            Node<K, V>[] table = buckets;
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private void resize() {
        Node<K, V>[] oldTable = buckets;
        int newCapacity = oldTable.length <= MAX_CAPACITY / 2
                ? oldTable.length * 2
                : MAX_CAPACITY;
        Node<K, V>[] newTable = newTable(newCapacity);

        for (Node<K, V> head : oldTable) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newCapacity);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }
        buckets = newTable;
    }

    private static int threshold(int capacity) {
        return Math.max(1, (int) (((long) capacity * 3) / 4));
    }

    private static int indexFor(int hash, int capacity) {
        return (hash & 0x7fffffff) % capacity;
    }

    private static int hash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
