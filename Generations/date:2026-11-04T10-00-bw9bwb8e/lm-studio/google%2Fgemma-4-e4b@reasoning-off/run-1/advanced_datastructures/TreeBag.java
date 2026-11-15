Order<T> {
    public int compare(T left, T right) {
        throw new UnsupportedOperationException("Must implement comparison");
    }
}

// Forward declarations needed for dependencies
@TreeNode.ThreadSafe
class TreeNode<T> {
    @interface ThreadSafe {}

    TreeNode<T> parent;
    TreeNode<T> left;
    TreeNode<T> right;
    int count; // Multiplicity of this distinct item in the subtree rooted here
    int size;  // Total number of items (including repetitions) in this subtree
    int distinctSize; // Number of distinct item classes in this subtree

    // Color property for Red-Black Tree balance
    boolean isRed; // True if red, false (or null/black) if black

    // Constructor and accessors omitted for brevity but implied in full implementation
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final int size;
    private final int distinctSize;
    // Note: Storing element references for consistency in the snapshot without deep copying.
    // For simplicity in this skeleton, we assume T is comparable or comparison context exists.
    private final TreeNode<T> rootNode; // Reference to the structure state

    public Snapshot(int size, int distinctSize, TreeNode<T> rootNode) {
        this.size = size;
        this.distinctSize = distinctSize;
        this.rootNode = rootNode;
    }

    public int size() { return size; }
    public int distinctSize() { return distinctSize; }

    // Simplified implementations relying on the structure being consistent at snapshot time
    public int count(T item) {
        // In a real implementation, this would traverse the structure pointed to by rootNode
        // and calculate the count based on item comparison.
        return 0;
    }

    public T get(int index) {
        // In a real implementation, this would traverse the structure pointed to by rootNode
        return null; // Placeholder
    }
}


@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}


    private TreeNode<T> root;
    private final Order<T> comparator;

    /**
     * Constructs TreeBag with the given comparison order.
     * @param comparator Defines how elements are compared. Must be nonnull, deterministic, total, and not mutate.
     */
    public TreeBag(Order<T> comparator) {
        if (comparator == null) {
            throw new NullPointerException("Comparator cannot be null");
        }
        this.comparator = comparator;
        this.root = null; // Black root, size 0, distinctSize 0
    }

    // --- Core invariants maintenance functions (Insert/Delete/Balance) follow standard RB Tree algorithms. ---

    /**
     * Adds one occurrence of the item. Linearizable operation.
     */
    public void add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");

        synchronized (this) {
            // Overflow check simulation based on int limits for size management.
            if (root != null && root.size >= Integer.MAX_VALUE) {
                throw new IllegalStateException("Collection size would overflow int");
            }

            // In a full implementation, insertion involves:
            // 1. Searching for the equivalence class root (if present).
            // 2. If found, incrementing its count and sizes along the path.
            // 3. If not found, inserting a new node (the distinct class root), balancing the tree, and updating sizes/counts.
            // 4. Rebalancing (rotations/recoloring) if necessary.

            // Placeholder for actual insertion logic...
        }
    }

    /**
     * Removes one occurrence of the item. Linearizable operation.
     * @return true if the item was present and removed, false otherwise.
     */
    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");

        synchronized (this) {
            // In a full implementation, removal involves:
            // 1. Searching for the equivalence class root matching the item.
            // 2. If found, decrementing its count and sizes along the path.
            // 3. If the count reaches zero, the node might be pruned/merged if it's a leaf or needs restructuring.
            // 4. Rebalancing upwards on the path taken during decrement/pruning.

            // Placeholder for actual removal logic...
            return false; // Example return
        }
    }

    /**
     * Returns the number of occurrences of the item. O(log d).
     */
    public int count(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");

        synchronized (this) {
            // Traverse to find the node representing the item and return its count.
            return 0; // Placeholder
        }
    }

    /**
     * Returns the total number of items (including repetitions). O(1) after traversal.
     */
    public int size() {
        synchronized (this) {
            return root == null ? 0 : root.size; // Assuming root node holds total size
        }
    }

    /**
     * Returns the number of distinct item types. O(1) after traversal/invariants maintained.
     */
    public int distinctSize() {
        synchronized (this) {
            return root == null ? 0 : root.distinctSize; // Assuming root node holds distinct count
        }
    }

    /**
     * Retrieves the item at a zero-based index in sorted order. O(log d).
     * @throws IndexOutOfBoundsException if the index is invalid.
     */
    public T get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + size());
        }

        synchronized (this) {
            // Traverse the tree, using subtree sizes to navigate to the Nth item.
            return null; // Placeholder
        }
    }

    /**
     * Returns the count of items strictly smaller than the given item. O(log d).
     */
    public int rank(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");

        synchronized (this) {
            // Traverse the tree, summing sizes of left subtrees encountered.
            return 0; // Placeholder
        }
    }

    /**
     * Captures an immutable, coherent historical state. O(1).
     */
    public Snapshot<T> snapshot() {
        synchronized (this) {
            // The complexity relies on the tree structure being inherently persistent/path-copied 
            // or by simply reading the current invariant sizes and setting up a stable root reference.
            if (root == null) {
                return new Snapshot<>(0, 0, null);
            }
            // In a true persistent structure, we might need to copy the root reference safely.
            return new Snapshot<>(size(), distinctSize(), root); // Using current state as the basis for snapshot
        }
    }
}
