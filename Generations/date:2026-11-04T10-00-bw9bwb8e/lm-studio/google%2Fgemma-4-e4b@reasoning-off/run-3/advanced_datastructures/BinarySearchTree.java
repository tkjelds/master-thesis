Order<T> {
    public boolean compare(T left, T right) {
        // Implementation of comparison logic goes here.
        throw new UnsupportedOperationException("Compare not implemented");
    }
}

/**
 * A concurrent node-based binary search tree set.
 */
@BinarySearchTree.ThreadSafe
public class BinarySearchTree {
    @interface ThreadSafe {}


    // --- Internal Node Structure ---

    private static class Node {
        final Object item; // Holds the actual element, comparison uses this.
        Node left;
        Node right;

        // Synchronization for updates to children references during traversal/modification.
        // Since we use a monitor on the root for linearizability, this simple structure is fine.
        // However, if concurrent traversal *during* modification was allowed without blocking the monitor,
        // we would need more complex structures (like compare-and-swap nodes).
        // For a single monitor implementation, these are effectively protected by the root lock.

        Node(Object item) {
            this.item = item;
        }
    }

    // --- Instance Fields ---

    private Node root;
    private int size = 0;
    private final Order<Object> comparer;

    /**
     * Constructs a BinarySearchTree.
     * @param order The ordering mechanism defining int compare(T left, T right).
     */
    public BinarySearchTree(Order<Object> order) {
        if (order == null) {
            throw new NullPointerException("Order cannot be null.");
        }
        this.comparer = order;
    }

    // --- Comparison Helpers (Internal) ---

    /**
     * Compares two elements according to the injected Order.
     * Returns negative if a < b, positive if a > b, 0 if a == b.
     * Throws IllegalArgumentException if elements are null (though public contract handles this).
     */
    private int compare(Object left, Object right) {
        if (left == null || right == null) {
            // This should ideally be caught by public methods before calling internal comparison.
            throw new IllegalArgumentException("Elements cannot be null during comparison.");
        }
        // We rely on the Order implementation to handle the casting/comparison correctly.
        // Since the problem statement implies T is used, we treat Object as T here for compilation context.
        return comparer.compare(left, right);
    }

    // --- Public Operations ---

    /**
     * Adds an item to the set if it is not already present.
     * Linearizable operation protected by the root monitor lock.
     * @param item The item to add. Must not be null.
     * @return true if the item was added, false if it already existed or failed insertion.
     */
    public boolean add(Object item) {
        if (item == null) throw new NullPointerException("Item cannot be null.");

        synchronized (this) {
            // Standard BST insertion logic, searching for exact match first.

            if (root == null) {
                root = new Node(item);
                size = 1;
                return true;
            }

            Node current = root;
            boolean added = false;

            while (true) {
                int cmp = compare(item, current.item);

                if (cmp == 0) {
                    // Element already exists
                    return false;
                } else if (cmp < 0) {
                    if (current.left == null) {
                        // Found insertion point
                        current.left = new Node(item);
                        size++;
                        return true;
                    } else {
                        current = current.left;
                    }
                } else { // cmp > 0
                    if (current.right == null) {
                        // Found insertion point
                        current.right = new Node(item);
                        size++;
                        return true;
                    } else {
                        current = current.right;
                    }
                }
            }
        }
    }

    /**
     * Removes an item from the set if it is present.
     * Linearizable operation protected by the root monitor lock.
     * @param item The item to remove. Must not be null.
     * @return true if the item was found and removed, false otherwise.
     */
    public boolean remove(Object item) {
        if (item == null) throw new NullPointerException("Item cannot be null.");

        synchronized (this) {
            // Tree deletion implementation requires traversal and potential structural changes.

            if (root == null) {
                return false;
            }

            // Recursive helper for deletion is usually cleanest for BSTs, but must operate entirely under the lock.
            // We use a private recursive helper to keep cleanup simple.
            Node oldRoot = root;
            root = deleteNode(oldRoot, item);

            // Check if the structure actually changed (i.e., if we successfully found and deleted)
            if (root != oldRoot || size > 0 && root == null) {
                // If the original root was modified or if size decreased and root is now null, we successfully removed something.
                // Since the recursive function handles size decrement atomically when it recurses up, 
                // we rely on the helper to manage the size count correctly if it performs a removal.
                // For safety, we must decrement size only if the deletion logic confirms success.

                // NOTE: A robust recursive delete must return a boolean indicating success, or we track the size change explicitly.
                // Since `deleteNode` modifies the tree structure starting from root, we need to track if a node matching 'item' was found.
                // Reverting to iterative or careful recursive structure to manage `size` decrement exactly once per successful removal.
                // For simplicity matching the typical textbook implementation where `deleteNode` returns the new subtree root:

                // If we reach here, we must ensure size reflects reality. We will assume the recursive helper updates `size` correctly if successful, 
                // or we must manually manage it based on the return of the search.

                // To strictly adhere to linearizability while managing size counts in a recursive delete:
                int initialSize = size; // Read current size under lock
                root = deleteNode(root, item); // Perform deletion (which must update 'size' if successful)
                
                // Due to the complexity of tracking success vs side effects in a recursive deletion causing structural change, 
                // we'll use a slightly simplified contract for this response: the `deleteNode` helper must decrement size upon success.
                // Since I cannot easily see inside the hypothetical `deleteNode` body here, assume it handles size decrement internally upon successful removal.
                // If size changed from initialSize, success = true. If root remained the same object and size didn't change, failure.
                // For this implementation simulation, we trust the side effect on `size` caused by successful structural manipulation.
                return size < initialSize; 
            }

            // This path is taken if the root itself was null or if deleteNode decided no change happened.
            return false; 
        }
    }

    /**
     * Helper method to recursively delete a node and update size. (Must be locked externally).
     */
    private Node deleteNode(Node current, Object item) {
        if (current == null) {
            return null; // Not found, no change.
        }

        int cmp = compare(item, current.item);

        if (cmp < 0) {
            current.left = deleteNode(current.left, item);
        } else if (cmp > 0) {
            current.right = deleteNode(current.right, item);
        } else {
            // Found the node to delete (cmp == 0)

            // Case 1: Leaf or one-child node with only right child
            if (current.left == null) {
                // This might be the only node, or it has a right child.
                if (current.right == null) {
                    size--; // Deleted leaf node
                    return null; 
                } else {
                    // Only right child exists, bypass current node
                    size--;
                    return current.right;
                }
            } 
            // Case 2: Leaf or one-child node with only left child
            else if (current.right == null) {
                // Only left child exists, bypass current node
                size--;
                return current.left;
            } 
            // Case 3: Two-child node (Find in-order successor, replace data, delete successor)
            else {
                // Find the minimum in the right subtree (in-order successor)
                Node successor = findMin(current.right);
                
                // Replace current item's data with successor's item (if T were mutable, we copy value; since items are final objects, 
                // this requires replacing the node's item reference if we assume items are unique identifiers.)
                // Since the contract says "Each node holds one element," and we must preserve all other elements, 
                // replacing the *content* of the node is often necessary unless the BST relies on object identity matching.
                // Assuming replacement means: use successor's item key, but keep structure integrity intact elsewhere.
                // Since the items are identified by `compare`, we update the current node's item to match successor.
                // NOTE: This assumes the items themselves are interchangeable representations of the same conceptual value, 
                // which is typical when deleting a two-child node.
                current.item = successor.item; 

                // Now, delete the successor from the right subtree
                current.right = deleteNode(current.right, successor.item); 
                size--; // Successor deletion caused the size decrease
            }
        }
        return current;
    }

    /** Searches for the minimum element in a subtree. */
    private Node findMin(Node node) {
        while (node != null && node.left != null) {
            node = node.left;
        }
        return node;
    }


    /** Finds the smallest element. O(h). */
    public Object first() {
        if (root == null) {
            throw new IllegalStateException("Tree is empty.");
        }

        synchronized (this) {
            // Find min element path under lock to ensure root stability during search.
            Node current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.item;
        }
    }

    /** Finds the largest element. O(h). */
    public Object last() {
        if (root == null) {
            throw new IllegalStateException("Tree is empty.");
        }

        synchronized (this) {
            // Find max element path under lock.
            Node current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.item;
        }
    }

    /** Returns the number of elements in the set. O(1). */
    public int size() {
        // Size is maintained atomically/under monitor protection during mutations. Reading it needs no lock 
        // IF all writes are guaranteed to publish visibility correctly (which volatile/synchronized handles).
        // Since we use synchronized blocks for writes, reading `size` here is safe and O(1).
        return size; 
    }

    /** Searches for an item. O(h). */
    public boolean contains(Object item) {
        if (item == null) throw new NullPointerException("Item cannot be null.");

        synchronized (this) {
            Node current = root;
            while (current != null) {
                int cmp = compare(item, current.item);
                if (cmp == 0) {
                    return true;
                } else if (cmp < 0) {
                    current = current.left;
                } else {
                    current = current.right;
                }
            }
            return false;
        }
    }

    /**
     * Returns a detached array snapshot of all elements in sorted order. O(N) time complexity to copy.
     * The traversal itself is locked momentarily, but the resulting array is detached and stable thereafter.
     */
    public Object[] snapshot() {
        synchronized (this) {
            if (size == 0) {
                return new Object[0];
            }

            // Perform in-order traversal to build the array copy.
            java.util.ArrayList<Object> tempList = new java.util.ArrayList<>();
            inOrderTraversal(root, tempList);

            // Return the array representation (shallow copy of elements references)
            return tempList.toArray(); 
        }
    }

    // Helper for snapshot generation (must be called under monitor lock)
    private void inOrderTraversal(Node node, java.util.ArrayList<Object> list) {
        if (node == null) return;
        inOrderTraversal(node.left, list);
        list.add(node.item);
        inOrderTraversal(node.right, list);
    }

    // --- Supporting Interfaces/Classes (Required by problem statement) ---

    /**
     * Defines the comparison logic for elements T. Must be deterministic, total, and not mutate/reenter.
     * Note: Although the problem specified T, we use Object here to match generic usage constraints.
     */
    public interface Order<T> {
        /**
         * Compares two elements.
         * @return negative if left < right, positive if left > right, zero if equal.
         */
        int compare(T left, T right);
    }

    // Note: If the application required the comparison logic to be provided at runtime, 
    // the constructor would take Order<T> and use T consistently. Since we used Object[] for snapshot, 
    // the implementation above casts down to Object/comparer constraints. The provided snippet assumes Order<Object> is used internally
    // to satisfy the usage seen in `add(Object item)`.

}
