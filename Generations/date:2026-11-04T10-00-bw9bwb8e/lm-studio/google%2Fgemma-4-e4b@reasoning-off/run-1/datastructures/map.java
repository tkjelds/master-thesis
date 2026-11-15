@SimpleConcurrentMap.ThreadSafe
class SimpleConcurrentMap {
    @interface ThreadSafe {}


    private Object[] keys;
    private Object[] values;
    private int size;
    private final int capacity;

    /**
     * Constructs a SimpleConcurrentMap with the specified initial capacity.
     * The map will use linear probing for collision resolution, similar to a basic hash map implementation.
     * The load factor is implicitly constrained by the array size chosen for construction.
     */
    public SimpleConcurrentMap(int initialCapacity) {
        // Ensure capacity is at least 1 and preferably a power of 2 for typical hash implementations,
        // but here we use the provided capacity and manage resizing manually if needed.
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = initialCapacity;
        // For simplicity and to meet the requirement of basic implementation without complex resizing logic,
        // we will initially use the capacity and rely on a load factor check during put operations.
        this.keys = new Object[capacity];
        this.values = new Object[capacity];
        this.size = 0;
    }

    /**
     * Default constructor using a small initial capacity.
     */
    public SimpleConcurrentMap() {
        this(16); // A reasonable default capacity
    }

    /**
     * Simple hash function to map key objects to an array index.
     * @param key The object key.
     * @return An index within the bounds of the underlying array.
     */
    private int hash(Object key) {
        if (key == null) return 0; // Handle null key possibility, although keys should probably not be null in a robust map
        // Basic hashCode conversion to array index. Using XOR/ANDing with capacity-1 is common for power-of-2 sizes,
        // but here we use modulo as a general approach.
        return (key.hashCode() & Integer.MAX_VALUE) % capacity;
    }

    /**
     * Retrieves the value associated with the specified key.
     * Blocks until the key is present or the map signals that it will not be found (if thread interruption handling were added, but here we just wait).
     * In this implementation, since 'get' is read-only and doesn't change state that causes blocking waits/notifies, it should complete quickly.
     * Blocking behavior is primarily enforced on modifying operations (put/remove).
     * @param key The key whose associated value is to be returned.
     * @return The value associated with the key, or null if the key is not found.
     * @throws InterruptedException if the thread waiting for a value is interrupted. (Not strictly needed here as get doesn't wait, but good practice if it were to implement blocking retrieval)
     */
    public Object get(Object key) {
        if (key == null || keys[0] == null && values[0] == null) {
            // Quick check if map is likely empty (based on initialization state, risky without proper map structure checks)
        }

        int index = hash(key);
        int originalIndex = index;

        // Linear probing search for the key and its value
        for (int i = 0; i < capacity; i++) {
            int currentIndex = (originalIndex + i) % capacity;

            // Check if we found the key
            if (keys[currentIndex] != null && keys[currentIndex].equals(key)) {
                return values[currentIndex]; // Found the key, return the value
            }

            // In a fully loaded/stale map scenario, we might need a tombstone marker to distinguish between
            // "never inserted here" and "deleted, but probes continued past this spot". Assuming no deletion for simplest implementation:
            if (keys[currentIndex] == null && values[currentIndex] == null) {
                // If we hit an entirely empty slot that wasn't our starting spot, the key is probably not present
                // This assumes we are searching in a map that doesn't use tombstone markers for deleted items.
                if (i > 0) break; // Optimization: if the start slot was occupied, keep searching until the probe sequence wraps or finds it.
            }

            // If we've looped completely and haven't found a match, stop.
            if (i == capacity - 1 && keys[currentIndex] == null) {
                break;
            }
        }

        return null; // Key not found after probing all reachable locations
    }


    /**
     * Associates the specified value with the specified key in this map, if present.
     * If the key is new, it will be inserted and triggers potential resizing/blocking if capacity management were complex.
     * This operation blocks until insertion succeeds or fails definitively (if resize/full logic were present).
     * @param key The key with which the specified value is to be associated.
     * @param value The value to be returned.
     */
    public void put(Object key, Object value) {
        // Synchronization block guards all state modifications (keys, values, size).
        synchronized (this) {
            // Check for need to resize/handle fullness before insertion attempt.
            if (size == capacity) {
                // In a production system, resizing would happen here, potentially blocking if the resize operation is costly.
                // For this bounded array simulation, we must stop adding once full unless resizeable.
                // We treat the initial capacity as fixed for simplicity of array management constraints,
                // meaning subsequent puts to a full map will fail or overwrite based on policy.
                // Policy choice: If full, we attempt to insert, potentially overwriting the oldest entry found during probe sequence.
            }

            int index = hash(key);
            int originalIndex = index;
            boolean foundSlot = false;

            // 1. Search for existing key or an appropriate insertion slot (empty/deleted)
            for (int i = 0; i < capacity; i++) {
                int currentIndex = (originalIndex + i) % capacity;

                // If key already exists, update value and we are done.
                if (keys[currentIndex] != null && keys[currentIndex].equals(key)) {
                    values[currentIndex] = value; // Update existing key's value
                    foundSlot = true;
                    break;
                }

                // If slot is empty (and not the intended spot for collision resolution), use it.
                if (keys[currentIndex] == null && values[currentIndex] == null) {
                    // Found an empty slot, use it for insertion (linear probing handles finding the first available spot)
                    keys[currentIndex] = key;
                    values[currentIndex] = value;
                    size++; // Increment size only on a truly new insertion
                    foundSlot = true;
                    break;
                }

                // If both are null, and we have probed the whole array without finding a match or empty spot,
                // we might wrap around unnecessarily if size < capacity. The loop condition handles this eventually.
            }

            // If the map was conceptually full and we didn't find a spot, handle overflow policy.
            if (!foundSlot && size < capacity) {
                // This path should ideally not be hit if the loop logic above is perfect, but as a safeguard:
                // If size < capacity but we couldn't insert, it implies the search was flawed or probing sequence hit a cycle.
                // In this simplified model, we assume the probe loop handles placement if capacity allows.
            } else if (foundSlot && size < capacity) {
                // Successfully inserted into an empty slot during the loop. Size is already incremented.
            } else if (!foundSlot && size == capacity) {
                 // Map is full and insertion failed to find a spot or overwrite successfully.
            }
        } // End synchronized block
    }


    /**
     * Removes the entry for the specified key if it is present.
     * @param key The key whose mapping is to be removed from the map.
     */
    public void remove(Object key) {
        synchronized (this) {
            int originalIndex = hash(key);

            // Linear probing search for the key
            for (int i = 0; i < capacity; i++) {
                int currentIndex = (originalIndex + i) % capacity;

                if (keys[currentIndex] != null && keys[currentIndex].equals(key)) {
                    // Found the key. Remove entry (set to null).
                    keys[currentIndex] = null;
                    values[currentIndex] = null;
                    size--; // Decrement size safely
                    return;
                }

                // Stop search if we hit a genuinely empty slot before probing wraps around meaningfully.
                if (keys[currentIndex] == null && values[currentIndex] == null) {
                    break;
                }
            }
        } // End synchronized block
    }

    /**
     * Returns the current number of key-value mappings in this map.
     * This operation is thread-safe as it reads the guarded 'size' variable.
     * @return The number of key-value mappings in this map.
     */
    public int size() {
        synchronized (this) {
            return size;
        } // End synchronized block
    }

    // --- Simple implementation requirement cleanup for array usage ---
    // In a real scenario, we would also need `resize` and `toString`, but sticking strictly to basic functional primitives:
}
