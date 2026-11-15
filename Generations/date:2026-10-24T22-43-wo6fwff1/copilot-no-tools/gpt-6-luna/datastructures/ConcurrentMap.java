public final class ConcurrentMap<K, V> {
    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Node<K, V>[] table;
    private int size;
    private int threshold;

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
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        table = (Node<K, V>[]) new Node[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    public V put(K key, V value) {
        requireKey(key);
        requireValue(value);

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

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Map is full");
            }

            if (size >= threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = indexFor(hash, table.length);
            }

            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = hash(key);
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                 node != null;
                 node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);

        synchronized (lock) {
            int hash = hash(key);
            for (Node<K, V> node = table[indexFor(hash, table.length)];
                 node != null;
                 node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        requireKey(key);

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

    @SuppressWarnings("unchecked")
    public void clear() {
        synchronized (lock) {
            table = (Node<K, V>[]) new Node[table.length];
            size = 0;
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Null keys are not supported");
        }
    }

    private static void requireValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }

    private static int hash(Object key) {
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int indexFor(int hash, int capacity) {
        return hash & (capacity - 1);
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int newCapacity = table.length << 1;
        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }

        Node<K, V>[] newTable = (Node<K, V>[]) new Node[newCapacity];
        for (int i = 0; i < table.length; i++) {
            Node<K, V> node = table[i];
            while (node != null) {
                Node<K, V> next = node.next;
                int index = indexFor(node.hash, newCapacity);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }

        table = newTable;
        threshold = newCapacity - (newCapacity >>> 2);
    }
}
