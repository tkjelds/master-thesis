@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Object[] locks;
    private final Node<K, V>[] table;

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int initialCapacity) {
        this.locks = new Object[initialCapacity];
        for (int i = 0; i < initialCapacity; i++) {
            locks[i] = new Object();
        }
        table = new Node[initialCapacity];
    }

    public V get(K key) {
        int index = hash(key);
        synchronized (locks[index]) {
            Node<K, V> node = table[index];
            while (node != null) {
                if (key.equals(node.key)) {
                    return node.value;
                }
                node = node.next;
            }
        }
        return null;
    }

    public boolean put(K key, V value) {
        int index = hash(key);
        synchronized (locks[index]) {
            Node<K, V> node = table[index];
            while (node != null) {
                if (key.equals(node.key)) {
                    node.value = value;
                    return false; // Key already existed
                }
                node = node.next;
            }
            Node<K, V> newNode = new Node<>(key, value);
            newNode.next = table[index];
            table[index] = newNode;
            return true; // Key added
        }
    }

    public boolean remove(K key) {
        int index = hash(key);
        synchronized (locks[index]) {
            Node<K, V> node = table[index];
            Node<K, V> prev = null;
            while (node != null) {
                if (key.equals(node.key)) {
                    if (prev == null) {
                        table[index] = node.next;
                    } else {
                        prev.next = node.next;
                    }
                    return true; // Key removed
                }
                prev = node;
                node = node.next;
            }
        }
        return false; // Key not found
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % table.length;
    }

    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
