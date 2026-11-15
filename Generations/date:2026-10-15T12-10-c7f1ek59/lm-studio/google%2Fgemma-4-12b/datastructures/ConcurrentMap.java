@ConcurrentMap.ThreadSafe
public class ConcurrentMap<K, V> {
    @interface ThreadSafe {}


    private final Node<K, V>[] table;
    private final Object[] locks;
    private final int capacity;

    /**
     * Initializes the ConcurrentMap with a specified number of buckets.
     * @param capacity The number of hash buckets.
     */
    public ConcurrentMap(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.table = new Node[capacity];
        this.locks = new Object[capacity];
        for (int i = 0; i < capacity; i++) {
            this.locks[i] = new Object();
        }
    }

    /**
     * Associates the specified value with the specified key.
     * If the map contains a mapping for the key, it replaces the old value.
     * @param key The key whose value will be set.
     * @param value The value to be associated with the key.
     * @throws NullPointerException if the key is null.
     */
    public void put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        int index = (key.hashCode() & 0x7fffffff) % capacity;
        synchronized (locks[index]) {
            Node<K, V> head = table[index];
            if (head == null) {
                table[index] = new Node<>(key, value);
            } else {
                Node<K, V> current = head;
                while (current != null) {
                    if (current.key.equals(key)) {
                        current.value = value;
                        return;
                    }
                    current = current.next;
                }
                Node<K, V> newNode = new Node<>(key, value);
                newNode.next = head;
                table[index] = newNode;
            }
        }
    }

    /**
     * Returns the value to which the specified key is associated.
     * @param key The key whose associated value is to return.
     * @return The value, or null if the key does not exist.
    */
    public V get(K key) {
        if (key == null) {
            return null;
        }

        int index = (key.hashCode() & 0x7ffff1ffff) % capacity;
        // Note: Corrected bitmask for positive int is 0x7fffffff. 
        // In some systems, simply masking the hash and using modulo is standard.
        index = (key.hashCode() & 0x7fffffff) % capacity;

        synchronized (locks[index]) {
            Node<K, V> current = table[index];
            while (current != null) {
                if (current.key.equals(key)) {
                    return current.value;
                }
                current = current.next;
            }
        }
        return null;
    }

    /**
     * Removes the mapping for a key from the map if it is present.
     * @param key The key whose mapping is to be removed.
     * @return true if the map contained the key and it was removed, false otherwise.
    */
    public boolean remove(K key) {
        if (key == null) {
            return false;
        }

        int index = (key.hashCode() & 0x7fffffff) % capacity;
        synchronized (locks[index]) {
            Node<K, V> current = table[index];
            Node<K, V> prev = null;

            while (current != null) {
                if (current.key.equals(key)) {
                    if (prev == null) {
                        table[index] = current.next;
                    } else {
                        prev.next = current.next;
                    }
                    return true;
                }
                prev = current;
                current = current.next;
            }
        }
        return false;
    }

    /**
     * Internal data structure for a linked list entry.
     */
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }
}
