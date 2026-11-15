@ThreadSafeConcurrentMap.ThreadSafe
public class ThreadSafeConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Node<K, V>[] table;
    private final int capacity;
    private int size = 0;

    @SuppressWarnings("unchecked")
    public ThreadSafeConcurrentMap(int capacity) {
        this.capacity = capacity;
        this.table = new Node[capacity];
    }

    public V put(K key, V value) {
        int index = hash(key);
        synchronized (lock(index)) {
            Node<K, V> node = table[index];
            if (node == null) {
                table[index] = new Node<>(key, value);
                size++;
                return null;
            }
            while (true) {
                if (node.key.equals(key)) {
                    V oldValue = node.value;
                    node.value = value;
                    return oldValue;
                }
                if (node.next == null) {
                    node.next = new Node<>(key, value);
                    size++;
                    return null;
                }
                node = node.next;
            }
        }
    }

    public V get(K key) {
        int index = hash(key);
        synchronized (lock(index)) {
            Node<K, V> node = table[index];
            while (node != null) {
                if (node.key.equals(key)) {
                    return node.value;
                }
                node = node.next;
            }
            return null;
        }
    }

    public V remove(K key) {
        int index = hash(key);
        synchronized (lock(index)) {
            Node<K, V> node = table[index];
            if (node == null) {
                return null;
            }
            if (node.key.equals(key)) {
                table[index] = node.next;
                size--;
                return node.value;
            }
            while (true) {
                if (node.next == null) {
                    return null;
                }
                Node<K, V> next = node.next;
                if (next.key.equals(key)) {
                    node.next = next.next;
                    size--;
                    return next.value;
                }
                node = next;
            }
        }
    }

    public int size() {
        return size;
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % capacity;
    }

    private Object lock(int index) {
        return table[index];
    }

    static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
