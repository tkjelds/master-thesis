@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private Node<K, V>[] table;
    private int size;
    private int threshold;

    private static final class Node<K, V> {
        final K key;
        final int hash;
        V value;
        Node<K, V> next;

        Node(K key, V value, int hash, Node<K, V> next) {
            this.key = key;
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0 || initialCapacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException("Invalid initial capacity");
        }

        int capacity = 1;
        while (capacity < initialCapacity) {
            capacity <<= 1;
        }

        table = (Node<K, V>[]) new Node[capacity];
        threshold = calculateThreshold(capacity);
    }

    private static int calculateThreshold(int capacity) {
        return capacity - (capacity >>> 2);
    }

    private static int hash(Object key) {
        if (key == null) {
            return 0;
        }
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static boolean keysEqual(Object first, Object second) {
        return first == second || (first != null && first.equals(second));
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

            if (size + 1 > threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            table[index] = new Node<K, V>(key, value, hash, table[index]);
            size++;
            return null;
        }
    }

    public V get(Object key) {
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

    public boolean containsKey(Object key) {
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

    public V remove(Object key) {
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

    public boolean containsValue(Object value) {
        synchronized (lock) {
            for (int i = 0; i < table.length; i++) {
                for (Node<K, V> node = table[i]; node != null; node = node.next) {
                    if (value == node.value || (value != null && value.equals(node.value))) {
                        return true;
                    }
                }
            }
            return false;
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
            table = (Node<K, V>[]) new Node[DEFAULT_CAPACITY];
            threshold = calculateThreshold(DEFAULT_CAPACITY);
            size = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int oldCapacity = table.length;
        int newCapacity = oldCapacity << 1;

        if (newCapacity <= 0 || newCapacity > MAXIMUM_CAPACITY) {
            newCapacity = MAXIMUM_CAPACITY;
        }
        if (newCapacity == oldCapacity) {
            threshold = Integer.MAX_VALUE;
            return;
        }

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
        threshold = calculateThreshold(newCapacity);
    }
}
