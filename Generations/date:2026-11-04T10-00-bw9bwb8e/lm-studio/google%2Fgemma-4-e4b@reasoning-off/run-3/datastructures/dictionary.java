@CustomConcurrentDictionary.ThreadSafe
class CustomConcurrentDictionary implements Runnable {
    @interface ThreadSafe {}


    private Object[] table;
    private int size;
    private static final int CAPACITY = 10; // A small fixed capacity for demonstration

    // Simple inner class to hold key-value pairs
    private static class Entry {
        Object key;
        Object value;

        Entry(Object key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    public CustomConcurrentDictionary() {
        // Initialize the table array. In a real implementation, we might use objects here,
        // but for simplicity mapping keys to indices modulo CAPACITY.
        table = new Entry[CAPACITY];
        size = 0; // Current number of elements (useful for tracking state)
    }

    /**
     * Inserts a key-value pair into the dictionary. If the key already exists, its value is updated.
     * This operation blocks if the dictionary reaches a predefined maximum capacity (if we wanted bounded behavior),
     * but for simplicity here, we assume insertion always succeeds unless the table is full due to limitations
     * not fully specified for blocking growth. We implement basic insertion assuming linear probing/simple structure management.
     * For this simple array simulation, we will require the caller to manage resizing if necessary,
     * or implement basic collision handling within CAPACITY.
     */
    public void put(Object key, Object value) {
        synchronized (this) {
            int index = hash(key);
            Entry current;

            // Simple linear probing attempt to find the key or an empty slot
            for (int i = 0; i < CAPACITY; i++) {
                int checkIndex = (index + i) % CAPACITY;
                current = table[checkIndex];

                if (current == null) {
                    // Found an empty slot, insert new entry
                    table[checkIndex] = new Entry(key, value);
                    size++;
                    return; // Insertion complete
                } else if (current.key.equals(key)) {
                    // Key found, update value and return
                    current.value = value;
                    return; // Update complete
                }
            }

            // If we reach here, the table is effectively full and could not insert/find.
            // In a real production system, this would trigger resizing or block on growth signal.
        }
    }

    /**
     * Retrieves the value associated with the given key. Returns null if the key is not found.
     */
    public Object get(Object key) {
        synchronized (this) {
            int index = hash(key);

            // Linear probing search
            for (int i = 0; i < CAPACITY; i++) {
                int checkIndex = (index + i) % CAPACITY;
                Entry current = table[checkIndex];

                if (current == null) {
                    // Hit an empty slot before finding the key, so key was never present (assuming tombstoning isn't used for deletion)
                    return null;
                } else if (current.key.equals(key)) {
                    // Key found, return value
                    return current.value;
                }
            }
            // Probed the entire structure without finding it
            return null;
        }
    }

    /**
     * Removes the key and its associated value from the dictionary. Returns the old value, or null if the key was not found.
     * Note: Simple deletion in a linear probing structure without tombstoning is problematic as it breaks search chains.
     * For this simplified simulation, we will mark the slot as null upon successful removal, assuming no subsequent lookups rely on chaining through this specific spot.
     */
    public Object remove(Object key) {
        synchronized (this) {
            int index = hash(key);

            for (int i = 0; i < CAPACITY; i++) {
                int checkIndex = (index + i) % CAPACITY;
                Entry current = table[checkIndex];

                if (current == null) {
                    return null; // Not found
                } else if (current.key.equals(key)) {
                    // Found the entry, remove it
                    Object oldValue = current.value;
                    table[checkIndex] = null; // Remove reference
                    size--;
                    return oldValue;
                }
            }
            return null; // Probed all slots, not found
        }
    }

    /**
     * Simple multiplicative hash function simulation for array indexing.
     */
    private int hash(Object key) {
        // A simple simulation: use the object's hashCode and map it to an index.
        int h = 1;
        if (key != null) {
            // Minimal effort to simulate dependence on the key object's identity/content
            h = key.hashCode(); 
        } else {
             // Handle null key scenario if necessary, default to a base hash
             h = 1;
        }
        return (Math.abs(h) % CAPACITY);
    }

    // --- Demonstration Runner (Runnable interface implementation) ---

    @Override
    public void run() {
        // This method is required by the Runnable interface but is not strictly part of the *Dictionary* API.
        // It allows testing/demonstrating usage in a thread context if needed.
    }

    // Main method for self-contained execution demonstration (optional, but useful)
    public static void main(String[] args) {
        CustomConcurrentDictionary dict = new CustomConcurrentDictionary();

        Runnable task1 = () -> {
            System.out.println("Thread 1 attempting operations...");
            dict.put("KeyA", "Value1");
            System.out.println("T1 Get KeyA: " + dict.get("KeyA"));
            dict.put("KeyB", "Value2");
        };

        Runnable task2 = () -> {
            // Wait slightly to encourage interleaving, though synchronization handles correctness.
            try { Thread.sleep(10); } catch (Exception e) {} 
            System.out.println("Thread 2 attempting operations...");
            dict.put("KeyA", "NewValue1"); // Update
            System.out.println("T2 Get KeyA: " + dict.get("KeyA"));
        };

        Thread t1 = new Thread(task1);
        Thread t2 = new Thread(task2);

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\nFinal check after threads finished:");
        System.out.println("Final KeyA value: " + dict.get("KeyA"));
    }
}
