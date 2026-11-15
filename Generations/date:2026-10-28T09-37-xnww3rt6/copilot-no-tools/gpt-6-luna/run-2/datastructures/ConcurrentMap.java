@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;

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
    private Node<K, V>[] table;
    private int size;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }
        table = (Node<K, V>[]) new Node[initialCapacity];
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            resizeIfNeeded();
            index = indexFor(hash, table.length);
            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = hash(key);
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                    node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
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
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                    node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public boolean containsValue(V value) {
        requireNonNull(value, "value");

        synchronized (lock) {
            for (Node<K, V> bucket : table) {
                for (Node<K, V> node = bucket; node != null; node = node.next) {
                    if (value.equals(node.value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == hash && key.equals(node.key)) {
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
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private void resizeIfNeeded() {
        int threshold = table.length - table.length / 4;
        if (size < threshold || table.length > Integer.MAX_VALUE / 2) {
            return;
        }

        @SuppressWarnings("unchecked")
        Node<K, V>[] newTable = (Node<K, V>[]) new Node[table.length * 2];

        for (Node<K, V> bucket : table) {
            Node<K, V> node = bucket;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newTable.length);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }
        table = newTable;
    }

    private static int hash(Object key) {
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return (hash & 0x7fffffff) % capacity;
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }
}
