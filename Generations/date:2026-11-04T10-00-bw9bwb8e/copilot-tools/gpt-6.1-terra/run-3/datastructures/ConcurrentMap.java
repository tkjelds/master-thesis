/**
 * A thread-safe hash map. Null keys and values are permitted.
 *
 * A null result from get, put, or remove can mean either that no mapping
 * existed or that the previous value was null; use containsKey to distinguish
 * these cases when no other thread can modify the mapping between calls.
 *
 * Operations may wait to acquire the map's intrinsic lock. There is no
 * capacity-based blocking or fairness guarantee. Keys must retain stable
 * hash codes and equality while stored, and their hashCode and equals methods
 * must not modify this map.
 */
@ConcurrentMap.ThreadSafe
public final class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Node<K, V>[] table = newTable(INITIAL_CAPACITY);
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

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    private static int hash(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean keysEqual(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
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

            Node<K, V> node = new Node<K, V>(hash, key, value, null);
            if (table.length < MAX_CAPACITY
                    && size >= table.length - (table.length >>> 2)) {
                resize();
                index = hash & (table.length - 1);
            }
            node.next = table[index];
            table[index] = node;
            size++;
            return null;
        }
    }

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
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private void resize() {
        Node<K, V>[] newTable = newTable(table.length << 1);
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
