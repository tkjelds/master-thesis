@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private static final int DEFAULT_CAPACITY = 16;
    private static final int MAXIMUM_CAPACITY = 1 << 30;

    private final Object mutex = new Object();
    private Object[] table;
    private int size;
    private int threshold;

    private static final class Node {
        final Object key;
        Object value;
        Node next;

        Node(Object key, Object value, Node next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive");
        }

        int capacity = 1;
        while (capacity < initialCapacity && capacity < MAXIMUM_CAPACITY) {
            capacity <<= 1;
        }

        table = new Object[capacity];
        threshold = capacity - (capacity >>> 2);
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (mutex) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);
            Node node = (Node) table[index];

            while (node != null) {
                if (node.key.equals(key)) {
                    V previous = valueOf(node.value);
                    node.value = value;
                    return previous;
                }
                node = node.next;
            }

            if (size + 1 > threshold && table.length < MAXIMUM_CAPACITY) {
                resize();
                index = hash & (table.length - 1);
            }

            table[index] = new Node(key, value, (Node) table[index]);
            size++;
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (mutex) {
            int hash = spread(key.hashCode());
            Node node = (Node) table[hash & (table.length - 1)];

            while (node != null) {
                if (node.key.equals(key)) {
                    return valueOf(node.value);
                }
                node = node.next;
            }
            return null;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (mutex) {
            int hash = spread(key.hashCode());
            Node node = (Node) table[hash & (table.length - 1)];

            while (node != null) {
                if (node.key.equals(key)) {
                    return true;
                }
                node = node.next;
            }
            return false;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (mutex) {
            int hash = spread(key.hashCode());
            int index = hash & (table.length - 1);
            Node previous = null;
            Node node = (Node) table[index];

            while (node != null) {
                if (node.key.equals(key)) {
                    if (previous == null) {
                        table[index] = node.next;
                    } else {
                        previous.next = node.next;
                    }
                    size--;
                    return valueOf(node.value);
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
            for (int i = 0; i < table.length; i++) {
                table[i] = null;
            }
            size = 0;
        }
    }

    private void resize() {
        int newCapacity = table.length << 1;
        Object[] newTable = new Object[newCapacity];

        for (int i = 0; i < table.length; i++) {
            Node node = (Node) table[i];
            while (node != null) {
                Node next = node.next;
                int index = spread(node.key.hashCode()) & (newCapacity - 1);
                node.next = (Node) newTable[index];
                newTable[index] = node;
                node = next;
            }
        }

        table = newTable;
        threshold = newCapacity - (newCapacity >>> 2);
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
    }

    @SuppressWarnings("unchecked")
    private V valueOf(Object value) {
        return (V) value;
    }
}
