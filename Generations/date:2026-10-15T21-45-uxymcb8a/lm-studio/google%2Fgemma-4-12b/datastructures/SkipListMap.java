@SkipListMap.ThreadSafe
public class SkipListMap<K extends Comparable<K>, V> {
    @interface ThreadSafe {}


    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V>[] next;

        @SuppressWarnings("unchecked")
        Node(K key, V value, int level) {
            this.key = key;
            this.value = value;
            this.next = new Node[level];
        }
    }

    private final Node<K, V> head;
    private final int MAX_LEVEL = 32;
    private long seed = System.currentTimeMillis();
    private final Object lockObj = new Object();

    public SkipListMap() {
        // Initialize the head node with a dummy value and max level.
        head = new Node<>(null, null, MAX_LEVEL);
    }

    /**
     * Inserts or updates a key-value pair in the map.
     * @param key The key to insert or update.
     * @param value The value associated with the key.
     */
    public void put(K key, V value) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        synchronized (lockObj) {
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> curr = head;

            // Locate the position for the key at each level
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                    curr = curr.next[i];
                }
                update[i] = curr;
            }

            // Move to the actual node level 0
            curr = (curr.next[0] == null) ? curr : curr.next[0];

            // If key exists, update value
            if (curr != null && curr.key.compareTo(key) == 0) {
                curr.value = value;
            } else {
                // Key does not exist, insert new node
                int level = generateRandomLevel();
                Node<K, V> newNode = new Node<>(key, value, level);

                for (int i = 0; i < level; i++) {
                    newNode.next[i] = update[i].next[i];
                    update[i].next[i] = newNode;
                }
            }
        }
    }

    /**
     * Retrieves the value associated with the given key.
     * @param key The key to find.
     * @return The value or null if the key is not found.
    */
    public V get(K key) {
        if (key == null) return null;
        synchronized (lockObj) {
            Node<K, V> curr = head;
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                    curr = curr.next[i];
                }
            }
            curr = (curr.next[0] == null) ? curr : curr.next[0];

            if (curr != null && curr.key.compareTo(key) == 0) {
                return curr.value;
            } else {
                return null;
            }
        }
    }

    /**
     * Removes the key-value pair from the map.
     * @param key The key to remove.
     * @return true if the mapping was removed, false if it did not exist.
    */
    public boolean remove(K key) {
        if (key == null) return false;
        synchronized (lockObj) {
            Node<K, V>[] update = new Node[MAX_LEVEL];
            Node<K, V> curr = head;

            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                    curr = curr.next[i];
                }
                update[i] = curr;
            }

            curr = (curr.next[0] == null) ? curr : curr.next[0];

            if (curr != null && curr.key.compareTo(key) == 0) {
                for (int i = 0; i < MAX_LEVEL; i++) {
                    if (update[i].next[i] != curr) {
                        break;
                    }
                    update[i].next[i] = curr.next[i];
                }
                return true;
            }
            return false;
        }
    }

    /**
     * Checks if the key exists in the map.
    * @param key The key to check.
    * @return true if the key exists, false otherwise.
    */
    public boolean contains(K key) {
        if (key == null) return false;
        synchronized (lockObj) {
            Node<K, V> curr = head;
            for (int i = MAX_LEVEL - 1; i >= 0; i--) {
                while (curr.next[i] != null && curr.next[i].key.compareTo(key) < 0) {
                    curr = curr.next[i];
                }
            }
            Node<K, V> target = (curr.next[0] == null) ? curr : curr.next[0];
            return target != null && target.key.compareTo(key) == 0;
        }
    }

    /**
     * Generates a random level for the skip list node height.
     */
    private int generateRandomLevel() {
        int level = 1;
        while (level < MAX_LEVEL && (nextLong() % 2 == 0)) {
            level++;
        }
        return level;
    }

    /**
     * Linear Congruential Generator to replace Math.random() without imports.
     */
    private long nextLong() {
        seed = (seed * 1103515245L + 12345L) & 0xFFFFFFFFL;
        return seed;
    }
}
