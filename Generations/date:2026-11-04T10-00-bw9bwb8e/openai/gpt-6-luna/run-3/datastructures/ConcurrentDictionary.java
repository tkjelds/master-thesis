@ConcurrentDictionary.ThreadSafe
public class ConcurrentDictionary<K, V> {
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

    private Node<K, V>[] table;
    private int size;

    public ConcurrentDictionary() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentDictionary(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("Initial capacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }
        table = (Node<K, V>[]) new Node[capacity];
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (this) {
            int hash = hash(key);
            int index = hash & (table.length - 1);

            for (Node<K, V> node = table[index]; node != null; node = node.next) {
                if (node.hash == hash && key.equals(node.key)) {
                    V oldValue = node.value;
                    node.value = value;
                    return oldValue;
                }
            }

            if (size >= threshold() && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            table[index] = new Node<K, V>(hash, key, value, table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
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
        requireNonNull(key, "key");

        synchronized (this) {
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

    public boolean containsValue(V value) {
        requireNonNull(value, "value");

        synchronized (this) {
            for (int i = 0; i < table.length; i++) {
                for (Node<K, V> node = table[i]; node != null; node = node.next) {
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

        synchronized (this) {
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
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public void clear() {
        synchronized (this) {
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
        @SuppressWarnings("unchecked")
        Node<K, V>[] newTable = (Node<K, V>[]) new Node[newCapacity];

        for (int i = 0; i < oldCapacity; i++) {
            Node<K, V> node = table[i];
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

    private int threshold() {
        return table.length - (table.length >>> 2);
    }

    private static int hash(Object key) {
        int value = key.hashCode();
        return value ^ (value >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }
}
