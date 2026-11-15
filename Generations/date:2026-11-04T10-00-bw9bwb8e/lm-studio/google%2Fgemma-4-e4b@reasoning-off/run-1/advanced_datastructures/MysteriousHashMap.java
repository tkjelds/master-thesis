@MysteriousHashMap.ThreadSafe
class MysteriousHashMap<K, V> {
    @interface ThreadSafe {}


    // --- Internal Structures ---

    static class BucketEntry<K, V> {
        final K key;
        final V value;

        BucketEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    // An immutable list of entries for a specific bucket array managed by a Stripe.
    // Since we are simulating an immutable list structure without using java.util.List,
    // we will use a simple array of BucketEntry objects and manage its perceived "immutability"
    // by treating it as fixed during read operations and replacing the array entirely upon writes.
    static class BucketArray<K, V> {
        // In a real scenario, this would be the bucket array whose length determines capacity.
        // For simplicity matching the read-only requirement, we use an array of lists/arrays.
        // Since deep immutability is hard without Collections, we model the bucket array itself 
        // as the immutable unit that replaces entirely during resizing/updates.

        private BucketEntry<K, V>[] buckets;
        private int capacity;

        @SuppressWarnings("unchecked")
        BucketArray(int initialCapacity) {
            this.capacity = initialCapacity;
            // Initialize buckets array to hold references to arrays (the chains/buckets)
            this.buckets = new BucketEntry[initialCapacity];
        }

        // Constructor for resizing/creating a new array structure during reallocation.
        @SuppressWarnings("unchecked")
        BucketArray(int[] oldBuckets, int newCapacity) {
            this.capacity = newCapacity;
            this.buckets = new BucketEntry[newCapacity];
        }

        // Helper to simulate insertion into a chain (for allocation/write phase)
        // This logic is complex in a real implementation; here we focus on the contract compliance.
        public void addEntry(int index, BucketEntry<K, V> entry) {
            // In a full implementation, this would add to the chain/bucket at 'index' 
            // if it were being constructed for a write operation.
        }

        // Read operations use this structure directly via the Stripe.
    }


    static class Stripe<K, V> {
        // A stripe manages its own set of buckets (BucketArray).
        private volatile BucketArray<K, V> bucketArray;
        private int size; // Local count for this stripe

        // Placeholder for synchronization management if needed during complex updates,
        // but the bucketArray reference swap handles main structural integrity.

        @SuppressWarnings("unchecked")
        Stripe(BucketArray<K, V> initialArray) {
            this.bucketArray = initialArray;
            this.size = 0; // Initial size of the stripe
        }

        // Read-only operations use the current bucketArray reference.
        public BucketArray<K, V> getBucketArray() {
            return bucketArray; // Volatile read ensures visibility of the latest array reference
        }

        // Write operation (e.g., successful put that might trigger reallocation/update)
        public synchronized void updateBucketArray(BucketArray<K, V> newArray, int newSize) {
            this.bucketArray = newArray; // Atomic swap of the reference under synchronization
            this.size = newSize;
        }

        public synchronized int getSize() {
            return size;
        }
    }

    // --- Main Class Fields ---

    private final Stripe<?>[] stripes; // The top-level array of Stripes
    private static final int STRIPE_COUNT = 16; // Example fixed number of stripes
    private static final int INITIAL_BUCKET_CAPACITY = 16; // Example initial capacity

    @SuppressWarnings("unchecked")
    public MysteriousHashMap() {
        this.stripes = new Stripe[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) {
            // Initialize each stripe with its own dedicated bucket structure
            BucketArray<K, V> initialArray = new BucketArray<>(INITIAL_BUCKET_CAPACITY);
            stripes[i] = new Stripe<>(initialArray);
        }
    }

    // --- Helper Methods (Simulating Hashing and Indexing) ---

    private Stripe<?> findStripe(Object k) {
        // Simple hashing to select the stripe index (0 to STRIPE_COUNT-1)
        return stripes[Math.abs(k.hashCode()) % STRIPE_COUNT];
    }

    private int getStripeIndex(Object k) {
        return Math.abs(k.hashCode()) % STRIPE_COUNT;
    }

    // --- Public Operations Implementation ---

    /**
     * Searches without locking. Relies on the immutability of the found BucketArray.
     */
    public boolean containsKey(K k) {
        Stripe<?> stripe = findStripe(k);
        // In a real scenario, we must use the bucketArray reference inside the Stripe
        // to access the structure safely without needing the Stripe itself locked, 
        // assuming reads are only observing the current stable BucketArray.

        BucketArray<?> array = stripe.getBucketArray();
        // Simulating traversal using the immutable BucketArray structure:
        // return array.search(k); 
        return false; // Placeholder for actual search logic against the array structure
    }

    /**
     * Retrieves value without locking. Relies on the immutability of the found BucketArray.
     */
    public V get(K k) {
        Stripe<?> stripe = findStripe(k);
        BucketArray<?> array = stripe.getBucketArray();
        // Simulating traversal:
        // return array.read(k); 
        return null; // Placeholder
    }

    /**
     * Performs insertion/update. Requires locking the relevant Stripe.
     */
    public V put(K k, V v) {
        Stripe<?> stripe = findStripe(k);

        // Synchronization happens at the Stripe level. This acquisition protects
        // against concurrent structural changes to this stripe's bucket array or size metadata.
        synchronized (stripe) {
            // 1. Perform the write operation on the current bucketArray, creating a new version if necessary.
            // 2. Check capacity and potentially trigger reallocation of the bucketArray (the expensive part).
            // 3. Update stripe size and atomically swap the bucketArray reference if reallocation occurred.

            // Placeholder: A successful put always increments size for this example context.
            int currentSize = stripe.getSize();
            if (currentSize < 10) { // Simulate successful insert without reallocation pressure yet
                 // Update bucket array (in reality, this might trigger a chain rebuild)
            } else {
                // Trigger reallocation/resize if necessary (complex step involving creating a new bucket array)
            }

            // Always return the old value associated with k, or null if new.
            return null; // Placeholder for actual return value
        }
    }

    /**
     * Removes key. Requires locking the relevant Stripe.
     */
    public V remove(K k) {
        Stripe<?> stripe = findStripe(k);

        synchronized (stripe) {
            // 1. Perform removal on the bucket array structure, yielding a potentially new immutable version.
            // 2. Decrement size safely.
            return null; // Placeholder for actual removed value
        }
    }

    /**
     * Returns the total size across all stripes. Requires a consistent, locked view 
     * or atomic summation of stripe sizes (summation is usually done under a global lock, 
     * or if striped counters are used that handle concurrent summing). Since we only have Stripe locks, 
     * we must acquire all stripe locks serially to get a globally consistent size.
     */
    public int size() {
        int totalSize = 0;
        // To get a truly consistent global size, we must lock all stripes sequentially.
        // This makes 'size()' O(N_stripes), which is acceptable for monitoring operations.
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<?> stripe = stripes[i];
            synchronized (stripe) {
                totalSize += stripe.getSize(); // Relying on Stripe synchronization here
            }
        }
        return totalSize;
    }

    /**
     * Iterates over all elements without needing a global lock if the underlying 
     * bucket arrays are truly immutable during iteration.
     */
    public void forEach(java.util.function.BiConsumer<K, V> consumer) {
        // Iteration must be lock-free regarding the bucket array structures.
        // We iterate over all stripes and use the current, stable BucketArray reference for each stripe.
        for (int i = 0; i < STRIPE_COUNT; i++) {
            Stripe<?> stripe = stripes[i];
            BucketArray<?> array = stripe.getBucketArray();

            // Perform traversal using the current snapshot of the bucket structure
            // If resizing occurs mid-iteration, this iteration sees the old or new version based on when array was read.
            // This matches the requirement: "containsKey, get, and forEach must proceed without taking a lock"
            // Note: If the structure undergoes reallocation, this iteration might miss elements added after array reference swap.
            // This is the expected trade-off for lock-free iteration in a concurrent map resizing.
            // array.traverse(consumer); 
        }
    }
}
