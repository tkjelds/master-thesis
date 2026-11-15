@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}

    private final Node<K, V>[] table;
    private final ReentrantLock[] locks;

    @SuppressWarnings("unchecked")
    public ConcurrentMap(int size) {
        table = new Node[size];
        locks = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            locks[i] = new ReentrantLock();
        }
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

    public V put(K key, V value) {
        int hash = hash(key);
        locks[hash].lock();
        try {
            Node<K, V> node = table[hash];
            if (node == null) {
                table[hash] = new Node<>(key, value);
                return null;
            } else {
                V oldValue = null;
                for (; node != null; node = node.next) {
                    if (node.key.equals(key)) {
                        oldValue = node.value;
                        node.value = value;
                        break;
                    }
                }
                if (oldValue == null) {
                    node.next = new Node<>(key, value);
                }
                return oldValue;
            }
        } finally {
            locks[hash].unlock();
        }
    }

    public V get(K key) {
        int hash = hash(key);
        locks[hash].lock();
        try {
            Node<K, V> node = table[hash];
            while (node != null) {
                if (node.key.equals(key)) {
                    return node.value;
                }
                node = node.next;
            }
            return null;
        } finally {
            locks[hash].unlock();
        }
    }

    public V remove(K key) {
        int hash = hash(key);
        locks[hash].lock();
        try {
            Node<K, V> node = table[hash];
            Node<K, V> prev = null;
            while (node != null) {
                if (node.key.equals(key)) {
                    V oldValue = node.value;
                    if (prev == null) {
                        table[hash] = node.next;
                    } else {
                        prev.next = node.next;
                    }
                    return oldValue;
                }
                prev = node;
                node = node.next;
            }
            return null;
        } finally {
            locks[hash].unlock();
        }
    }

    private int hash(K key) {
        return Math.abs(key.hashCode()) % table.length;
    }
}
