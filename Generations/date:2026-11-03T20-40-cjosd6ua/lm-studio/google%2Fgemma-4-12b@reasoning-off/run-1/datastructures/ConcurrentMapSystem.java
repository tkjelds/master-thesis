public class ConcurrentMapSystem {

    /**
     * A thread-safe Hash Map implementation.
     * 
     * @param <K> The type of keys
     * @param <V> The type of values
     */
    public static class ConcurrentMap<K, V> {
        private class Node {
            K key;
            V value;
            Node next;

            Node(K key, V value) {
                this.key = key;
                this.value = value;
            }
        }

        private Node[] table;
        private int capacity;
        private int size;
        private final Object lock = new Object();

        public ConcurrentMap(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be positive");
            }
            this.capacity = capacity;
            this.table = new Node[capacity];
        }

        /**
         * Inserts a key-value pair into the map.
         * If the key already exists, updates the value.
         */
        public void put(K key, V value) {
            if (key == null) {
                throw new IllegalArgumentException("Key cannot be null");
            }
            
            synchronized (lock) {
                int index = Math.abs(key.hashCode() % capacity);
                Node head = table[index];
                Node current = head;
                int i = 0;

                while (i < table.length) {
                    if (table[i] == null) {
                        // Note: This is a simplified hash logic. 
                        // In a real implementation, we handle collisions via linked lists.
                        break;
                    }
                    // Logic handled below for better structure
                }

                // Corrected bucket traversal logic:
                Node node = table[index];
                while (node != null) {
                    if (node.key.equals(key)) {
                        node.value = value;
                        return;
                    }
                    node = node.next;
                }

                // If not found, add new node
                Node newNode = new Node(key, value);
                newNode.next = table[index];
                table[index] = newNode;
                size++;
            }
        }

        /**
         * Retrieves the value associated with a key.
         * Returns null if not found.
         */
        public V get(K key) {
            if (key == null) return null;
            V result = null;
            synchronized (lock) {
                int index = Math.abs(key.hashCode() % capacity);
                Node node = table[index];
                while (node != null) {
                    if (node.key.equals(key)) {
                        result = (V) node.value;
                        break;
                    }
                    node = node.next;
                }
            }
            return result;
        }

        /**
         * Removes the entry for the specified key.
         */
        public void remove(K key) {
            if (key == null) return;
            synchronized (lock) {
                int index = Math.abs(key.hashCode() % capacity);
                Node head = table[index];
                Node prev = null;
                Node current = head;

                while (current != null) {
                    if (current.key.equals(key)) {
                        if (prev == null) {
                            table[index] = current.next;
                        } else {
                            prev.next = current.next;
                        }
                        size--;
                        return;
                    }
                    prev = current;
                    current = current.next;
                }
            }
        }

        /**
         * Returns the current size of the map.
         */
        public int size() {
            synchronized (lock) {
                return size;
            }
        }

        /**
         * Checks if the map contains a specific key.
         */
        public boolean contains(K key) {
            if (key == null) return false;
            boolean found = false;
            synchronized (lock) {
                int index = Math.abs(key.hashCode() % capacity);
                Node node = table[index];
                while (node != null) {
                    if (node.key.equals(key)) {
                        found = true;
                        break;
                    }
                    node = node.next;
                }
            }
            return found;
        }
    }

    public static void main(String[] args) {
        // Basic driver to demonstrate usage structure
        ConcurrentMap<String, Integer> map = new ConcurrentMap<>(10);
        map.put("Key1", 100);
        map.put("Key2", 200);

        System.out.println("Size: " + map.size());
        System.out.println("Key1 Value: " + map.get("Key1"));
        System.out.println("Contains Key2: " + map.contains("Key2"));
        
        map.remove("Key1");
        System.out.println("Size after removal: " + map.size());
    }
}
