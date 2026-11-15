/**
 * A monitor-protected dictionary. Null keys and values are supported.
 *
 * Keys must retain stable hashCode and equals behavior while stored. Their
 * hashCode and equals methods must not modify this dictionary.
 *
 * Operations may block while another operation holds the monitor. The
 * dictionary grows as needed; if its entry count reaches Integer.MAX_VALUE,
 * inserting a new key throws IllegalStateException.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
    @interface ThreadSafe {}

    private static final int INITIAL_CAPACITY = 16;
    private static final int MAX_CAPACITY = 1 << 30;

    private final Object monitor = new Object();
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

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int capacity) {
        return (Node<K, V>[]) new Node<?, ?>[capacity];
    }

    public ConcurrentDictionary() {
        synchronized (monitor) {
            table = newTable(INITIAL_CAPACITY);
        }
    }

    private static int hash(Object key) {
        int h = key == null ? 0 : key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean keysEqual(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    /**
     * Associates key with value and returns the previous value, or null if
     * there was no previous mapping. Use containsKey to distinguish a
     * missing mapping from a mapping to null.
     */
    public V put(K key, V value) {
        synchronized (monitor) {
            int h = hash(key);
            int index = h & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == h && keysEqual(key, node.key)) {
                    V oldValue = node.value;
                    node.value = value;
                    return oldValue;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary is too large");
            }

            if (table.length < MAX_CAPACITY
                    && size >= table.length - (table.length >>> 2)) {
                resize();
                index = h & (table.length - 1);
            }

            table[index] = new Node<K, V>(h, key, value, table[index]);
            size++;
            return null;
        }
    }

    /**
     * Returns the mapped value, or null if the key is absent or mapped to null.
     */
    public V get(K key) {
        synchronized (monitor) {
            int h = hash(key);
            int index = h & (table.length - 1);
            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == h && keysEqual(key, node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        synchronized (monitor) {
            int h = hash(key);
            int index = h & (table.length - 1);
            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == h && keysEqual(key, node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes the mapping and returns its previous value, or null if the
     * key was absent or mapped to null.
     */
    public V remove(K key) {
        synchronized (monitor) {
            int h = hash(key);
            int index = h & (table.length - 1);
            Node<K, V> previous = null;
            Node<K, V> node = table[index];

            while (node != null) {
                if (node.hash == h && keysEqual(key, node.key)) {
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
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (monitor) {
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    // Called only while holding monitor.
    private void resize() {
        Node<K, V>[] newBuckets = newTable(table.length << 1);
        for (int i = 0; i < table.length; i++) {
            Node<K, V> node = table[i];
            while (node != null) {
                Node<K, V> next = node.next;
                int index = node.hash & (newBuckets.length - 1);
                node.next = newBuckets[index];
                newBuckets[index] = node;
                node = next;
            }
        }
        table = newBuckets;
    }
}
