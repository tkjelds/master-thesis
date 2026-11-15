@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object mutex = new Object();
    private Node<K, V>[] table;
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

    public ConcurrentMap() {
        table = createTable(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }
        table = createTable(capacity);
    }

    public V get(K key) {
        int hash = hashOf(requireKey(key));
        synchronized (mutex) {
            Node<K, V> node = findNode(key, hash);
            return node == null ? null : node.value;
        }
    }

    public boolean containsKey(K key) {
        int hash = hashOf(requireKey(key));
        synchronized (mutex) {
            return findNode(key, hash) != null;
        }
    }

    public boolean containsValue(V value) {
        requireValue(value);
        synchronized (mutex) {
            for (Node<K, V> head : table) {
                for (Node<K, V> node = head; node != null; node = node.next) {
                    if (value.equals(node.value)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);
        int hash = hashOf(key);

        synchronized (mutex) {
            Node<K, V> existing = findNode(key, hash);
            if (existing != null) {
                V previous = existing.value;
                existing.value = value;
                return previous;
            }

            if (size + 1 > resizeThreshold(table.length)
                    && table.length < MAXIMUM_CAPACITY) {
                resize();
            }

            int index = hash & (table.length - 1);
            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V remove(K key) {
        int hash = hashOf(requireKey(key));
        synchronized (mutex) {
            int index = hash & (table.length - 1);
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
        synchronized (mutex) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (mutex) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (mutex) {
            table = createTable(table.length);
            size = 0;
        }
    }

    private Node<K, V> findNode(K key, int hash) {
        int index = hash & (table.length - 1);
        for (Node<K, V> node = table[index]; node != null; node = node.next) {
            if (node.hash == hash && key.equals(node.key)) {
                return node;
            }
        }
        return null;
    }

    private void resize() {
        int oldCapacity = table.length;
        int newCapacity = oldCapacity >= MAXIMUM_CAPACITY / 2
                ? MAXIMUM_CAPACITY
                : oldCapacity << 1;
        Node<K, V>[] newTable = createTable(newCapacity);

        for (Node<K, V> head : table) {
            Node<K, V> node = head;
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newCapacity - 1);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }

        table = newTable;
    }

    private static int resizeThreshold(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private static int hashOf(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static <T> T requireKey(T key) {
        if (key == null) {
            throw new NullPointerException("Keys must not be null");
        }
        return key;
    }

    private static <T> T requireValue(T value) {
        if (value == null) {
            throw new NullPointerException("Values must not be null");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] createTable(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
