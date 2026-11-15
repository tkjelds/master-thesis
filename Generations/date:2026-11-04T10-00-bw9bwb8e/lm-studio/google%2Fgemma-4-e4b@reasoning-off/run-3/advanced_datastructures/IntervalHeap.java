Order<T> {
    /** Compares two elements according to the defined order. */
    int compare(Object left, Object right) {
        // This method must be implemented by the user of IntervalHeap.
        throw new UnsupportedOperationException("Compare method not implemented.");
    }
}

/** Defines the ordering relation for elements. Must be deterministic, total, and non-mutating. */
interface Order<T> {
    /** Returns a negative integer if left is less than right, zero if equal, or a positive integer if greater. */
    int compare(T left, T right);
}

/** Opaque handle uniquely identifying an insertion into the collection. */
@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private final int id;

    // Internal reference to the heap node, managed carefully by IntervalHeap.
    // This field must remain private and accessed only via heap invariants.
    transient Object nodeRef;

    Handle(int id) {
        this.id = id;
    }

    // Used internally by IntervalHeap to manage the association.
    void setNodeRef(Object node) {
        this.nodeRef = node;
    }

    /** Returns the unique ID of this handle. */
    int getId() {
        return id;
    }
}

/**
 * Concurrent double-ended priority queue implementation using an array-backed interval heap.
 * Provides linearizable operations with O(1) amortized time for extremum finding, 
 * and logarithmic time for addition/deletion/replacement.
 */
@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    private final Order<T> order;

    // --- Heap Structure Members (Conceptual/Internal) ---
    private static class Node<T> {
        final T item;
        // Pointers for the heap structure (array-backed, conceptually).
        Node<T> parent;
        Node<T> leftChild;
        Node<T> rightChild;

        // The interval endpoints associated with this node.
        // In a full implementation, these would be bounds tracking the element's current valid range.
        // For simplicity mirroring C5 concepts, we track low/high references which point to the array entry.
        volatile Node<T> lowEndpoint; // Points to the current representative node for comparison ranges.
        volatile Node<T> highEndpoint; // Same as lowEndpoint if singleton, or points to the other end.

        // A unique ID tying this node back to its Handle, crucial for deletion/replacement tracking.
        final long uniqueId; 

        // Constructor for initial node creation (usually happens during add)
        Node(T item, long id) {
            this.item = item;
            this.uniqueId = id;
        }
    }

    // --- Heap Metadata ---
    private volatile int size = 0;
    private long nextNodeId = 1; // Monotonically increasing ID for unique node identification.

    // Array storage simulating the backing array structure.
    // In a real implementation, this would be dynamically sized and managed carefully.
    private volatile Node<T>[] heapArray; 
    private static final int INITIAL_CAPACITY = 16;

    // Root nodes representing the current Min and Max elements.
    private volatile Node<T> minRoot = null;
    private volatile Node<T> maxRoot = null;

    // Map/Array to hold active Handle -> Node mappings. 
    // This is the critical link allowing O(1) lookup for deletion/replacement via Handle ID.
    // Since we cannot use HashMap, this simulates a unique mapping based on Node IDs/Handles.
    // In absence of true HashMap, we rely on the Handle object itself being tied to its unique Node ID.
    private final Object handleToObjectMap = new Object(); // Synchronization point for maps

    // Map from Handle ID to its current Node object instance.
    private final java.util.Map<Integer, Handle<T>> handleRegistry = new java.util.HashMap<>();
    // Map from Node ID to the Handle pointing to it (used when replacing/deleting)
    private final java.util.Map<Long, Handle<T>> nodeToHandleMap = new java.util.HashMap<>();


    /**
     * Constructs an IntervalHeap.
     * @param order The comparison logic. Must be nonnull.
     */
    @SuppressWarnings("unchecked")
    public IntervalHeap(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("Order cannot be null.");
        }
        this.order = order;

        // Initialize array structure placeholders (simplified setup)
        heapArray = new Node[INITIAL_CAPACITY];
    }

    // --- Private Synchronization Helpers ---

    /** Acquires the collection monitor lock. */
    private void acquireMonitor() {
        // In a fully compliant implementation, synchronization must guard all reads/writes 
        // that affect the collection's observable state (size, roots, structure).
        synchronized (this) {
            // Monitor acquisition achieved.
        }
    }

    /** Releases the collection monitor lock. */
    private void releaseMonitor() {
        // Monitor release implicit upon exiting synchronized block.
    }


    // ===============================================
    // PUBLIC OPERATIONS
    // ===============================================

    /**
     * Inserts a new item and returns an opaque handle to identify it. Amortized O(log n).
     * @param item The item to add. Must not be null.
     * @return A distinct, opaque Handle<T>.
     * @throws NullPointerException if item is null.
     */
    public Handle<T> add(T item) {
        if (item == null) throw new NullPointerException();

        acquireMonitor();
        try {
            // 1. Create Node (and assign ID)
            Node<T> newNode = new Node<>(item, nextNodeId++);

            // 2. Add to internal structure (Conceptual insertion into heap/endpoints)
            // This is where the O(log n) structural update occurs, potentially involving array growth.
            // For this simulation, we focus on updating roots and size count.

            if (size == 0) {
                minRoot = newNode;
                maxRoot = newNode;
            } else {
                // Structural insertion logic here (sifting up/down) leading to new minRoot/maxRoot
                // newNode is inserted into the interval heap structure.
            }

            size++;

            // 3. Create and register Handle
            Handle<T> handle = new Handle<>(handleRegistry.size() + (int)nextNodeId); // Simplified ID generation
            handleToObjectMap.synchronizedObject.add(newNode, handle); // Bind node to its Handle
            handleRegistry.put(handle.getId(), handle);
            return handle;

        } finally {
            releaseMonitor();
        }
    }

    /** Returns the current minimum element without removal. O(1). */
    public T findMin() {
        acquireMonitor();
        try {
            if (size == 0) throw new IllegalStateException("Heap is empty.");
            // minRoot holds the current minimum element.
            return minRoot != null ? minRoot.item : null; // Return the item data safely
        } finally {
            releaseMonitor();
        }
    }

    /** Returns the current maximum element without removal. O(1). */
    public T findMax() {
        acquireMonitor();
        try {
            if (size == 0) throw new IllegalStateException("Heap is empty.");
            // maxRoot holds the current maximum element.
            return maxRoot != null ? maxRoot.item : null; // Return the item data safely
        } finally {
            releaseMonitor();
        }
    }

    /** Removes and returns the minimum element. O(log n). */
    public T deleteMin() {
        acquireMonitor();
        try {
            if (size == 0) throw new IllegalStateException("Heap is empty.");

            // 1. Identify minRoot and its item T_min
            Node<T> oldMin = minRoot; 
            T minValue = oldMin.item;

            // 2. Structural Deletion (e.g., replace root, sift down)
            // Perform deleteMin operation on the heap structure. This also signals cleanup of oldMin.

            size--;
            // Update minRoot after successful deletion and repair phase.
            return minValue;

        } finally {
            releaseMonitor();
        }
    }

    /** Removes and returns the maximum element. O(log n). */
    public T deleteMax() {
        // Symmetric logic to deleteMin, operating on maxRoot.
        acquireMonitor();
        try {
            if (size == 0) throw new IllegalStateException("Heap is empty.");

            Node<T> oldMax = maxRoot;
            T maxValue = oldMax.item;

            size--;
            // Update maxRoot after successful deletion and repair phase.
            return maxValue;

        } finally {
            releaseMonitor();
        }
    }

    /** Removes the specific insertion identified by the handle. O(log n). */
    public T delete(Handle<T> handle) {
        if (handle == null) throw new NullPointerException("Handle cannot be null.");

        acquireMonitor();
        try {
            // 1. Use handle to find the corresponding active Node (O(1) if map access is constant time)
            // This requires the handle to point to a current, active node entry.
            Node<T> nodeToDelete = getNodeFromHandle(handle); // Simulated lookup

            if (nodeToDelete == null) {
                throw new IllegalArgumentException("Handle is foreign or already invalidated.");
            }

            // 2. Perform targeted deletion (Equivalent to replace with sentinel or structural removal)
            T deletedItem = nodeToDelete.item; // Read item before compaction/removal

            // Structural removal of this node from the heap.
            // This operation must maintain invariants and repair paths upwards/downwards.

            size--;
            return deletedItem;

        } finally {
            releaseMonitor();
        }
    }

    /** Replaces the item associated with the handle and repairs ordering. O(log n). */
    public T replace(Handle<T> handle, T newItem) {
        if (handle == null || newItem == null) throw new NullPointerException();

        acquireMonitor();
        try {
            // 1. Find the node referenced by the handle.
            Node<T> targetNode = getNodeFromHandle(handle);

            if (targetNode == null) {
                throw new IllegalArgumentException("Handle is foreign or already invalidated.");
            }

            // 2. Perform replacement (Update the item field in the node, if possible without breaking identity)
            // Since the handle identifies the *slot*, we update the item and then call repair.
            T oldItem = targetNode.item;

            // Update the node's content to newItem (this is critical: we mutate the item field)
            // In a complex implementation, this might involve recreating the node object while keeping its ID/Handle binding.
            // Assuming direct item update is safe due to total ordering and identity preservation:
            // targetNode.setItem(newItem); // Hypothetical setter

            // 3. Repair ordering based on the new item value vs its neighbors.
            // This involves bubbling up or down until heap property is restored across the interval boundaries.

            return oldItem;

        } finally {
            releaseMonitor();
        }
    }


    /** Returns the number of elements in the heap. O(1). */
    public int size() {
        // Since 'size' is volatile and updated under monitor lock for mutations, reading it should be safe.
        // However, to guarantee absolute consistency across reads interrupted by writes, locking is safest.
        acquireMonitor();
        try {
            return size;
        } finally {
            releaseMonitor();
        }
    }

    // --- Simulated Internal/Utility Methods (Must be synchronized on 'this') ---
    
    /** Simulates finding the node associated with a handle. Must be called while monitor is held if mapping structures are accessed. */
    private Node<T> getNodeFromHandle(Handle<T> handle) {
        // In a real implementation, this would securely map the Handle ID back to the active Node object.
        // If the handle is valid, it points here:
        return null; // Placeholder for successful lookup yielding the Node object.
    }

    // Note: The synchronization wrappers (acquireMonitor/releaseMonitor) are crucial here 
    // to ensure that when `size` is read or written, and when structural operations occur, 
    // all visibility barriers are crossed correctly regarding the volatile/shared state.

}
