MysteriousHashMap {

    // --- Internal Structures ---

    // Represents a single key-value pair entry.
    private static class Entry {
        final Object key;
        Object value;

        Entry(Object key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    // Represents the bucket array managed by a Stripe.
    // Since buckets are immutable lists of entries, this object represents the current state of the bucket array.
    private static class BucketArray {
        // An array where each element is an immutable list of Entries.
        // For simplicity and to model the "immutable list" requirement without using actual List implementations,
        // we will use an array of Entry objects representing the chain in that bucket.
        final Entry[] buckets;
        final int capacity; // Size of the bucket array

        BucketArray(int capacity) {
            this.capacity = capacity;
            this.buckets = new Entry[capacity]; // In a real implementation, these would be initialized to represent empty lists/chains
        }

        // Simplified helper for demonstration: usually involves traversal and checking capacity.
        // Here, we treat this array conceptually as the bucket structure for a stripe.

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BucketArray)) return false;
            BucketArray other = (BucketArray) obj;
            if (this.capacity != other.capacity) return false;
            // In a full implementation, deep equality check of all buckets would go here.
            return true; // Assume conceptual equality for structure comparison validation might be needed during reallocation check.
        }
    }

    // Represents a single Stripe, responsible for its portion of the map.
    private static class Stripe {
        // The current bucket array for this stripe. Volatile ensures visibility of updates across stripes.
        volatile BucketArray bucketArray;
        // Tracks the number of entries this stripe is responsible for. Synchronization needed for size updates across stripes.
        int count;

        Stripe(BucketArray initialArray) {
            this.bucketArray = initialArray;
            this.count = 0;
        }

        // In a real implementation, this method would check fullness and atomically replace bucketArray if reallocation is needed.
        // This highly simplified implementation skips the full CAS loop for reallocation but sketches the intent.
        BucketArray readArray() {
            return bucketArray;
        }

        // Returns a new BucketArray structure after applying changes (e.g., insertion/deletion).
        // In a production system, this involves creating a new array structure based on the old one + change.
        BucketArray updateAndCommit(BucketArray newArray, int sizeChange) {
            // Simulate optimistic replacement and validation if needed.
            this.bucketArray = newArray;
            this.count += sizeChange; // Assuming count is managed correctly in a real scenario.
            return newArray;
        }

        // Synchronization context for applying structural changes (like reallocation or complex insertions/deletions)
        // In a real map, this Stripe instance itself might be the lock object for its bucket array updates.
    }

    // --- MysteriousHashMap Fields ---

    private final Stripe[] stripes;
    private static final int NUM_STRIPES = 16; // Arbitrary number of stripes
    private static final int INITIAL_BUCKET_CAPACITY = 16; // Initial bucket array size per stripe

    /**
     * Constructor initializes the structure with NUM_STRIPES stripes.
     */
    public MysteriousHashMap() {
        this.stripes = new Stripe[NUM_STRIPES];
        for (int i = 0; i < NUM_STRIPES; i++) {
            // Each stripe starts with its own bucket array.
            BucketArray initialArray = new BucketArray(INITIAL_BUCKET_CAPACITY);
            stripes[i] = new Stripe(initialArray);
        }
    }

    // --- Utility Methods ---

    /** Determines which stripe is responsible for this key. */
    private Stripe getStripeForKey(Object k) {
        // Simple hashing modulo NUM_STRIPES.
        int hashCode = (k == null) ? 0 : System.identityHashCode(k);
        int index = hashCode & (NUM_STRIPES - 1); // Masking for power-of-two size
        return stripes[index];
    }

    /** Default implementation for simpler use, assuming the entry structure allows deep copies/views when needed. */
    private int getStripeIndex(Object k) {
        int hashCode = (k == null) ? 0 : System.identityHashCode(k);
        return hashCode & (NUM_STRIPES - 1);
    }

    // --- Public Operations ---

    /**
     * Checks if the map contains the specified key. Read operation, requires no lock on stripes/bucket arrays.
     */
    public boolean containsKey(Object k) {
        Stripe stripe = getStripeForKey(k);
        // Read phase: No lock taken. Uses volatile reads for visibility of the current bucketArray reference.
        BucketArray array = stripe.bucketArray;

        // In a real implementation, traversal happens here using array references.
        return traverseBucketArray(array, k);
    }

    /**
     * Retrieves the value associated with the specified key. Read operation, requires no lock on stripes/bucket arrays.
     */
    public Object get(Object k) {
        Stripe stripe = getStripeForKey(k);
        // Read phase: No lock taken.
        BucketArray array = stripe.bucketArray;

        // Returns the value or null if not found.
        return traverseBucketArray(array, k);
    }

    /**
     * Associates the specified value with the specified key in this map if present. Write operation, requires locking.
     */
    public Object put(Object k, Object v) {
        Stripe stripe = getStripeForKey(k);

        // Synchronization must occur to ensure atomicity of size change and structural update.
        // We synchronize on the Stripe object as its count and bucketArray are managed here.
        synchronized (stripe) {
            // 1. Check current array and attempt update logic.
            BucketArray currentArray = stripe.bucketArray;

            // Simplified transactional update flow:
            Object oldValue = performPutTransaction(currentArray, k, v);

            // 2. After successful modification (in a real map, this might involve checking if reallocation is needed),
            // commit the change by potentially swapping BucketArray references.
            if (currentArray != stripe.bucketArray) {
                // If the operation resulted in a structure change (e.g., reallocation), the transaction function handles the swap.
            } else {
                // If no structure change, we are done with synchronization on this stripe.
            }

            return oldValue;
        }
    }

    /**
     * Removes the mapping for a key if it is present. Write operation, requires locking.
     */
    public Object remove(Object k) {
        Stripe stripe = getStripeForKey(k);

        synchronized (stripe) {
            BucketArray currentArray = stripe.bucketArray;

            // Perform removal transactionally. If successful, decrement size.
            Object removedValue = performRemoveTransaction(currentArray, k);

            // If the removal causes extreme sparsity, a garbage collection/shrink might be needed (complex).

            return removedValue;
        }
    }

    /** Returns the number of key-value mappings in this map. Read operation on size count, but requires synchronization for consistency across stripes if scaling is complex. */
    public int size() {
        // Calculating total size requires locking all stripes sequentially to prevent races during iteration.
        int totalSize = 0;
        for (Stripe stripe : stripes) {
            synchronized (stripe) {
                // Read the count atomically with respect to updates happening in the stripe.
                totalSize += stripe.count; // Assuming count is correctly maintained by transactional methods
            }
        }
        return totalSize;
    }

    /** Iterates over the mappings in this map. Read operation, requires no lock on stripes/bucket arrays (though access during iteration must be careful). */
    public void forEach(java.util.function.BiConsumer<Object, Object> consumer) {
        // To guarantee stability during iteration (like ConcurrentHashMap's entrySet view), we must iterate over all stripes
        // and read their bucket arrays. Since buckets are immutable lists, traversing them is safe without locking against writers,
        // as long as the underlying structure reference (`bucketArray`) doesn't change mid-traversal.

        for (Stripe stripe : stripes) {
            BucketArray array = stripe.bucketArray; // Read current reference non-atomically across all stripes
            // Traverse the buckets in this stripe's array instance.
            traverseBucketArray(array, (k, v) -> consumer.accept(k, v));
        }
    }

    // ==============================================================
    // SIMULATION/STUBS for transactional operations and traversals.
    // In a real implementation, these methods would contain the complex logic of bucket traversal and replacement.
    // ==============================================================

    private Object traverseBucketArray(BucketArray array, Object k) {
        // Simulates searching through the bucket structure associated with 'array' for key 'k'.
        // Returns found value or null.
        return null; // Placeholder
    }

    private void traverseBucketArray(BucketArray array, java.util.function.BiConsumer<Object, Object> action) {
        // Simulates iterating over all entries in the buckets of 'array' and applying 'action'.
    }

    private Object performPutTransaction(BucketArray currentArray, Object k, Object v) {
        // Simulates finding the entry location within bucket structures governed by currentArray.
        // Returns old value if replacement occurred, or null if new insertion.
        return null; // Placeholder for transaction outcome
    }

    private Object performRemoveTransaction(BucketArray currentArray, Object k) {
        // Simulates finding and logically removing the entry. Decrements count if successful.
        return null; // Placeholder for removed value
    }


    // --- Simplified Example Usage Note ---
    // The implementations above sketch the required concurrency control points:
    // 1. Read operations (containsKey, get, forEach traversal) use the volatile reference to BucketArray without locks.
    // 2. Write operations (put, remove, size calculation) acquire a lock on the respective Stripe object before modification.
    // 3. Reallocation and complex structural changes are gated behind the Stripe lock, ensuring atomic swaps of the bucket array reference.

}
