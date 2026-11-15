@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

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
    private int threshold;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        table = newTable(capacity);
        threshold = capacity - (capacity >>> 2);
    }

    public V get(K key) {
        synchronized (lock) {
            int hash = hash(key);
            Node<K, V> node = table[indexFor(hash, table.length)];
            while (node != null) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return node.value;
                }
                node = node.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            Node<K, V> node = table[indexFor(hash, table.length)];
            while (node != null) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public boolean containsValue(V value) {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                Node<K, V> node = table[i];
                while (node != null) {
                    if (valuesEqual(node.value, value)) {
                        return true;
                    }
                    node = node.next;
                }
            }
            return false;
        }
    }

    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
                node = node.next;
            }

            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;

            if (size > threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
            }
            return null;
        }
    }

    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = indexFor(hash, table.length);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == hash && keysEqual(node.key, key)) {
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

    private void resize() {
        int oldCapacity = table.length;
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            return;
        }

        int newCapacity = oldCapacity << 1;
        Node<K, V>[] newTable = newTable(newCapacity);

        for (int i = 0; i < oldCapacity; i++) {
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

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static int indexFor(int hash, int length) {
        return hash & (length - 1);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static boolean valuesEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
