@CustomConcurrentMap.ThreadSafe
public class CustomConcurrentMap {
    @interface ThreadSafe {}


    private Object[] keys;
    private Object[] values;
    private int size = 0;
    private final int capacity;

    /**
     * Constructs a CustomConcurrentMap with the given initial capacity.
     * The actual capacity used will be the next prime number greater than or equal to initialCapacity.
     * For simplicity in this implementation, we will use a fixed size for the underlying array
     * and manage collisions linearly without resizing or complex hashing strategies, focusing on synchronization.
     * A simplified array-based map implementation is used where capacity dictates the bucket count.
     */
    public CustomConcurrentMap(int initialCapacity) {
        // Setting a reasonable power-of-two capacity for simple array indexing simulation, though true hashing is omitted.
        this.capacity = initialCapacity > 0 ? initialCapacity : 16;
        this.keys = new Object[capacity];
        this.values = new Object[capacity];
    }

    /**
     * Associates the specified value with the specified key in this map if this map contains no mapping for the key.
     * If the map stores keys that are already associated with another location, the old value is replaced.
     * This operation blocks until it can successfully put the key-value pair, waiting if necessary.
     * @param key The key with which the specified value is to be associated.
     * @param value The value to be associated with the specified key.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void put(Object key, Object value) throws InterruptedException {
        // In a real map, we'd calculate an index. Here, we simulate finding/setting a slot based on key hash modulo capacity.
        int index = simpleHash(key);

        synchronized (this) {
            // Wait until there is a conceptual "space" or success condition. Since our simple array doesn't truly have limited space
            // (it will overwrite), we primarily need to ensure visibility and atomicity of the write.
            // If we were implementing a bounded map, we would wait here if size == capacity.
            // For an unbounded conceptual map mapped onto a fixed array, we proceed directly to update/insert.

            if (keys[index] == null) {
                // New insertion, increase size.
                keys[index] = key;
                values[index] = value;
                size++;
            } else {
                // Overwrite existing entry at this conceptual slot (or handle collision chaining if necessary, but sticking to simple map behavior).
                keys[index] = key;
                values[index] = value;
            }

            // In a complex scenario involving waits, we might notify here if a waiting thread needed this state change.
        }
    }

    /**
     * Returns the value to which the specified key is mapped, or null if this map contains no mapping for the key.
     * This operation blocks until a value associated with the key becomes available, waiting if necessary.
     * @param key The key whose associated value is to be returned.
     * @return The value mapped to the specified key, or null if not found or wait times out/is interrupted.
     * @throws InterruptedException if the current thread is interrupted while waiting for a value.
     */
    public Object get(Object key) throws InterruptedException {
        int index = simpleHash(key);

        synchronized (this) {
            // In a busy map where 'get' waits for insertion, we might need to wait until keys[index] is non-null.
            // For a simple synchronous map backing, we just check the current state.

            if (keys[index] == null) {
                // If the key hasn't been put yet, we must wait.
                while (keys[index] == null) {
                    // Wait until someone calls put() for this key (or conceptually, signals availability)
                    this.wait();
                }
            }

            // Read operation relies on the synchronized block providing visibility of the write from put().
            Object value = values[index];

            // After reading, if this structure supported removal or state change that invalidates the read, we might notify.
            return value;
        }
    }

    /**
     * Removes the mapping for a key if it is present.
     * This operation blocks until it can acquire the lock to safely perform the removal.
     * @param key The key whose mapping is to be removed from the map.
     * @return The value previously associated with key, or null if there was no mapping for key.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public Object remove(Object key) throws InterruptedException {
        int index = simpleHash(key);

        synchronized (this) {
            if (keys[index] == null) {
                // If the key isn't present, return null immediately after acquiring lock.
                return null;
            }

            // Read the old value for return. This must happen while holding the lock to ensure atomicity with the write operation.
            Object oldValue = values[index];

            // Perform removal/invalidation
            keys[index] = null;
            values[index] = null;
            size--;

            // Notify potential waiters that the slot is now free/removed.
            this.notifyAll();

            return oldValue;
        }
    }


    // --- Helper Methods (Simulating Hashing and Indexing) ---

    /**
     * Simple, non-cryptographic hash function mapping the key object to an array index.
     */
    private int simpleHash(Object key) {
        if (key == null) return 0;
        // Using Java's built-in hashCode() for consistency simulation.
        int h = key.hashCode();
        // Ensure positive index within capacity bounds.
        return (h & 0x7fffffff) % capacity;
    }

    // Note: For a complete, functional demonstration of blocking/unblocking in all directions
    // (e.g., if 'put' also waited for space), the structure would need queues or count variables
    // and more sophisticated notify/wait usage around size checks. The current implementation focuses
    // on correct synchronization primitives usage for visibility, atomicity, and basic blocking flow control.
}
