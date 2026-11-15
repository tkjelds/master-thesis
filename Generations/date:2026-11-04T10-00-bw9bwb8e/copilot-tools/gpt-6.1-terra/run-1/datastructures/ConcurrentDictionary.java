/**
 * A thread-safe dictionary with non-null keys and values.
 *
 * Operations acquire an intrinsic monitor but never wait for the dictionary
 * to become nonempty or for space to become available. Keys must have stable
 * hashCode and equals behavior while stored, and those methods must not modify
 * this dictionary. The dictionary protects its own structure, not the mutable
 * state of keys or values.
 */
@ConcurrentDictionary.ThreadSafe
public final class ConcurrentDictionary<K, V> {
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
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    /**
     * Associates the key with the value and returns its previous value,
     * or null if the key was not present.
     */
    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V previous = node.value;
                    node.value = value;
                    return previous;
                }
            }

            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Dictionary is too large");
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
     * Returns the value associated with the key, or null if it is absent.
     */
    public V get(K key) {
        requireNonNull(key);

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return node.value;
                }
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);

        synchronized (lock) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Removes the key and returns its previous value, or null if it is absent.
     */
    public V remove(K key) {
        requireNonNull(key);

        synchronized (lock) {
            int hash = hash(key);
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
