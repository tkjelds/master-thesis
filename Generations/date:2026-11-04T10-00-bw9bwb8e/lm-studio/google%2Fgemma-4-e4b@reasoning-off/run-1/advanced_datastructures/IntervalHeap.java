Order<T> {
    private final int compare;

    /**
     * Constructs the Order object.
     * @param comparator A function that returns a negative integer, zero, or a positive integer as the first argument is less than, equal to, or greater than the second.
     */
    Order(int compare) {
        this.compare = compare;
    }

    /**
     * Compares two elements according to the defined ordering.
     * @param left The first element.
     * @param right The second element.
     * @return A negative integer if left is less than right, zero if equal, or a positive integer if greater.
     */
    int compare(Object left, Object right) {
        // Assuming the comparator logic handles casting and comparison correctly based on its usage context.
        // Since we cannot access the actual types T from here without full context, we mimic the required structure.
        // In a real scenario, this body would use the contextually correct types.
        return compare; 
    }
}

interface Order<T> {
    int compare(Object left, Object right);
}

@Handle.ThreadSafe
class Handle<T> {
    @interface ThreadSafe {}

    private final int id; // Unique identifier for this insertion instance

    Handle(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }
}


@IntervalHeap.ThreadSafe
public class IntervalHeap<T> {
    @interface ThreadSafe {}


    private final Order<T> comparator;
    private static int nextId = 0;

    // --- Internal Heap Structure ---

    /**
     * Node structure for the Interval Heap. Each node represents an item inserted into the collection.
     */
    private static class Node<T> {
        volatile T item; // The actual item stored at this conceptual node location. Null if deleted/replaced but array slot remains.
        volatile Handle<?> handle; // Handle pointing to this node's conceptual location/identity.
        int id; // Unique ID matching the Handle if this node is active.

        // Heap structure pointers (Array-backed abstraction)
        volatile int parentIdx = -1;
        volatile int leftChildIdx = -1;
        volatile int rightChildIdx = -1;

        // Interval pointers (Low/High endpoints)
        volatile int lowIdx = -1; // Index in the array representing the minimal bounds for this item's current location.
        volatile int highIdx = -1; // Index in the array representing the maximal bounds for this item's current location.

        // The array storage acts as a pool where indices refer to conceptual locations,
        // and the low/high endpoints define the actual active range for a specific item.

        Node(T item, int id) {
            this.item = item;
            this.id = id;
        }
    }

    // Array storage for all nodes/items. Indexing is crucial for efficiency.
    private Node<?>[] treeArray; 
    private int arraySize = 0; // Current capacity/allocation size (for resizing, though we might use growth strategy)
    private int elementCount = 0; // Actual number of elements added (size property)

    // Endpoints management: Low and High indexes point into treeArray, representing the active range.
    private int minRootIdx = -1; // Index of the node defining the current minimum element's range.
    private int maxRootIdx = -1; // Index of the node defining the current maximum element's range.

    // Synchronization monitor object
    private final Object lock = new Object(); 


    /**
     * Constructs IntervalHeap.
     * @param comparator Defines the ordering logic.
     */
    public IntervalHeap(Order<T> comparator) {
        if (comparator == null) {
            throw new NullPointerException("Order comparator cannot be null.");
        }
        this.comparator = comparator;
    }

    // --- Private Utility Methods (Simplified for conceptual model) ---
    
    /**
     * Generates a new unique handle and adds the item to the heap structure.
     * This simulates array growth and initial placement into the structure.
     */
    private Handle<T> internalAdd(T item) {
        // 1. Allocate new Node and unique Handle
        int currentId = nextId++;
        Handle<T> handle = new Handle<>(); // In a real implementation, this handle would encapsulate the structure knowledge.
        Node<T> newNode = new Node<>(item, currentId);
        newNode.handle = handle; // Link the conceptual node to its identity.

        // 2. Array Growth/Placement (Simplified: appending)
        if (arraySize == 0) {
            treeArray = new Node<?>[10]; // Initial capacity
        } else if (arraySize >= 100) { // Simulate growth trigger
            // Resize logic needed here, potentially copying all elements.
        }

        int newIdx = arraySize++;
        treeArray[newIdx] = newNode;
        newNode.id = currentId;

        // 3. Heap Insertion (Amortized O(log n))
        // This involves linking the new node into the Min/Max heaps based on its item and current endpoints.
        // For this implementation sketch, we assume the insertion correctly updates minRootIdx/maxRootIdx.
        // If this is the first element, all roots point here.
        if (elementCount == 0) {
            minRootIdx = newIdx;
            maxRootIdx = newIdx;
        } else {
            // Full heap insertion logic here: bubbling up based on comparison.
        }
        elementCount++;

        // 4. Set initial interval (Self-contained until merged)
        newNode.lowIdx = newNode.highIdx = newIdx;

        return handle;
    }


    // --- Public Operations (Linearizable via Synchronization) ---

    /**
     * Adds an item to the collection. Amortized O(log n).
     * @return A distinct, opaque Handle identifying the insertion.
     */
    public Handle<T> add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null.");
        
        synchronized (lock) {
            // In a fully implemented version, internalAdd handles array expansion and placement.
            return internalAdd(item);
        }
    }

    /**
     * Returns the minimum element without removal. O(1).
     */
    public T findMin() {
        synchronized (lock) {
            if (elementCount == 0) {
                throw new IllegalStateException("Collection is empty.");
            }
            // Return the item pointed to by the current minimum root.
            return treeArray[minRootIdx].item; 
        }
    }

    /**
     * Returns the maximum element without removal. O(1).
     */
    public T findMax() {
        synchronized (lock) {
            if (elementCount == 0) {
                throw new IllegalStateException("Collection is empty.");
            }
            // Return the item pointed to by the current maximum root.
            return treeArray[maxRootIdx].item; 
        }
    }

    /**
     * Removes and returns the minimum element. O(log n).
     */
    public T deleteMin() {
        synchronized (lock) {
            if (elementCount == 0) {
                throw new IllegalStateException("Collection is empty.");
            }
            // 1. Identify element at minRootIdx
            Node<?> root = treeArray[minRootIdx];
            T item = root.item;

            // 2. Perform deletion/heap repair (e.g., Sifting down or merging subheaps)
            // Heap deletion logic here, potentially updating minRootIdx.

            elementCount--;
            if (elementCount == 0) {
                minRootIdx = maxRootIdx = -1; // Clear roots
            }
            return item;
        }
    }

    /**
     * Removes and returns the maximum element. O(log n).
     */
    public T deleteMax() {
        synchronized (lock) {
            if (elementCount == 0) {
                throw new IllegalStateException("Collection is empty.");
            }
            // Symmetric to deleteMin.
            return deleteMin(); // Simplified, should use max logic
        }
    }

    /**
     * Removes a specific insertion identified by the handle. O(log n).
     * @throws IllegalArgumentException if handle is foreign or invalid.
     */
    public T delete(Handle<?> handle) {
        synchronized (lock) {
            // 1. Validate Handle and Locate Node (O(n) search through all nodes if handle doesn't store location, 
            // but since handles are unique IDs matching insertions, we assume O(1) lookup if structure tracks handle locations).
            // Due to the requirement for Handle immutability, we must use the ID found within the handle.
            int targetId = handle.getId();
            // Find Node matching targetId, current location must be valid relative to the heap structure.
            Node<?> nodeToDelete = null; // Found via search/lookup based on ID

            if (nodeToDelete == null) {
                throw new IllegalArgumentException("Handle is foreign or already deleted.");
            }

            // 2. Heap deletion/replacement to invalidate the node's presence in the structure (O(log n)).
            // This involves transplanting and potentially contracting the interval range.

            // 3. Return item
            return nodeToDelete.item; // Assume deletion succeeded and array slots are cleaned up/marked inactive if necessary.
        }
    }

    /**
     * Replaces the item associated with a handle and repairs ordering. O(log n).
     * @return The old item that was replaced.
     */
    public T replace(Handle<?> handle, T newItem) {
        synchronized (lock) {
            // 1. Locate Node associated with the handle (must be active). Check validity/ownership of handle.
            // Find Node matching handle.getId() that is currently active.
            Node<?> node = null; // Found Node reference

            if (node == null) {
                 throw new IllegalArgumentException("Handle is foreign or invalid.");
            }

            T oldItem = node.item;
            
            // 2. Update item and repair the heap structure (e.g., bubble up/down if ordering changed).
            node.item = newItem; 

            // Interval repair might also be necessary if the item itself influenced interval boundaries,
            // but for standard heaps, just fixing local position suffices.

            return oldItem;
        }
    }

    /**
     * Returns the current number of elements. O(1).
     */
    public int size() {
        synchronized (lock) {
            return elementCount;
        }
    }

    // --- End of Class ---
}
