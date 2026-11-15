/**
 * A thread-safe, dynamically growing hash map.
 *
 * Null keys and values are supported. A null result from get, put, or remove
 * can mean either an absent mapping or a mapping whose value is null; use
 * containsKey to distinguish these cases.
 *
 * Operations wait only to acquire the map's monitor. The map has no fixed
 * capacity, although allocation can fail if memory is exhausted.
 *
 * Keys must have stable hashCode and equals results while stored in the map.
 * These methods must not modify this map.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
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
    private Node<K, V>[] table = newTable(INITIAL_CAPACITY);
    private int size;

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int hash = key.hashCode();
        return hash ^ (hash >>> 16);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
    }

    public V get(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Associates the key with the value and returns its previous value, or null
     * if there was no previous mapping.
     */
    public V put(K key, V value) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && keysEqual(key, node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Map size limit reached");
            }

            if (table.length < MAX_CAPACITY
                    && size >= table.length - table.length / 4) {
                grow();
                index = hash & (table.length - 1);
            }

            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    /**
     * Removes the mapping and returns its previous value, or null if absent.
     */
    public V remove(K key) {
        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == hash && keysEqual(key, node.key)) {
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
            table = newTable(INITIAL_CAPACITY);
            size = 0;
        }
    }

    private void grow() {
        Node<K, V>[] newTable = newTable(table.length * 2);
        for (int i = 0; i < table.length; i++) {
            Node<K, V> node = table[i];
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newTable.length - 1);
                node.next = newTable[index];
                newTable[index] = node;
                node = next;
            }
        }
        table = newTable;
    }
}
