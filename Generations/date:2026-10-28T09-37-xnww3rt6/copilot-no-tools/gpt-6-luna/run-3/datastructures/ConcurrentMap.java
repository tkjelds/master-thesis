@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object mutex = new Object();
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

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity must not be negative");
        }

        int capacity = 1;
        int requested = Math.max(1, Math.min(initialCapacity, MAXIMUM_CAPACITY));
        while (capacity < requested) {
            capacity <<= 1;
        }

        table = newTable(capacity);
        threshold = calculateThreshold(capacity);
    }

    public V put(K key, V value) {
        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            table[index] = new Node<>(hash, key, value, table[index]);
            size++;

            if (size > threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
            }
            return null;
        }
    }

    public V get(K key) {
        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(node.key, key)) {
                    return true;
                }
            }
            return false;
        }
    }

    public V remove(K key) {
        synchronized (mutex) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
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
            table = newTable(table.length);
            size = 0;
        }
    }

    private void resize() {
        Node<K, V>[] oldTable = table;
        int newCapacity = oldTable.length << 1;
        Node<K, V>[] newTable = newTable(newCapacity);

        for (Node<K, V> head : oldTable) {
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
        threshold = calculateThreshold(newCapacity);
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    private static int calculateThreshold(int capacity) {
        return Math.max(1, capacity - (capacity >>> 2));
    }

    @SuppressWarnings("unchecked")
    private Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node[capacity];
    }
}
