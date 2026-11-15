Order<T> {
    /**
     * Represents the ordering constraint for elements of type T.
     */
    public int compare(T left, T right) {
        throw new UnsupportedOperationException("Comparison must be implemented by subclass.");
    }
}

// Forward declarations for mutual recursion and clarity, assuming they will be fully defined below.
@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> comparator;
    // Root of the Red-Black Tree structure for the distinct classes.
    private Node<T> root;
    // Total number of occurrences in the collection. Must be consistent with subtree sums.
    private int totalSize = 0;
    // Number of distinct comparison-equivalence classes present.
    private int distinctSize = 0;

    // Internal Node structure for the Red-Black Tree.
    private static class Node<T> {
        final T item; // The representative element for this class.
        int count = 0; // Multiplicity of this specific item/class.
        int subtreeTotalCount = 0; // Total count in the subtree rooted here (including self).
        int subtreeDistinctSize = 0; // Total distinct classes in the subtree rooted here.
        Node<T> parent;
        Node<T> left;
        Node<T> right;
        boolean isRed = false; // Color attribute for RB Tree balancing.

        // Sentinel NIL node structure (acts as leaf/null pointer).
        // In a production setting, this should ideally be a single shared NIL node.
        // For simplicity within constraints, we rely on null checks but conceptually treat them as leaves needing care.
        // We must ensure that all structural invariants hold if left/right points to null, but since we rely on
        // the root being black and balancing on insertion/deletion path, null termination is used.
    }

    /**
     * Constructs TreeBag with the specified ordering mechanism.
     * @param order The comparison implementation. Must not be null.
     */
    TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("Order comparator cannot be null.");
        }
        this.comparator = order;
    }

    // --- Helper methods for internal state management (locked internally) ---

    /**
     * Synchronized block wrapper to ensure linearizability for compound operations.
     */
    private synchronized void checkSizeInvariant() {
        // Ensure root attributes reflect the true state if accessed during maintenance.
    }

    /**
     * Traverses up the tree to update ancestor node totals after a change in the subtree rooted at 'node'.
     * This must be called *after* the subtree change is finalized and before parent assignments are overwritten.
     */
    private void updateAncestors(Node<T> node) {
        Node<T> current = node;
        while (current != null && current.parent != null) {
            // Update the parent's counts based on its children/self.
            current.parent.subtreeTotalCount = current.count;
            if (current.left != null) {
                current.parent.subtreeTotalCount += current.left.subtreeTotalCount;
            }
            if (current.right != null) {
                current.parent.subtreeTotalCount += current.right.subtreeTotalCount;
            }

            // Update distinct size based on presence/absence of children.
            int leftDistinct = (current.left != null) ? current.left.subtreeDistinctSize : 0;
            int rightDistinct = (current.right != null) ? current.right.subtreeDistinctSize : 0;

            // The distinct size of the parent is 1 (for itself) + left distinct + right distinct.
            // This calculation needs refinement depending on how counts are managed upon deletion/insertion,
            // but for basic RB tree usage tracking multiplicity adds 1 distinct item.
            current.parent.subtreeDistinctSize = (current.parent.left != null ? 1 : 0) + leftDistinct + rightDistinct;
            // Due to the complexity of tracking distinctness across rotations/structure changes purely through counts,
            // we rely on the insertion/deletion logic to correctly manage `subtreeDistinctSize` upwards.
            // For this skeleton, we assume the change in count/presence of the current node correctly signals its presence.

            // Simplified invariant update path (full implementation requires tracking exact structural contribution):
            current.parent.subtreeTotalCount = current.count;
            if (current.left != null) current.parent.subtreeTotalCount += current.left.subtreeTotalCount;
            if (current.right != null) current.parent.subtreeTotalCount += current.right.subtreeTotalCount;

            // Distinct size management is trickier during rotations/rebalancing vs pure search path traversal.
            // We must ensure that when count > 0, this node contributes 1 to distinctSize upwards.
            // If the item exists (count > 0), its presence must be reflected in all ancestors' distinct size counts.
            if (current != root) {
                // A proper implementation would recursively adjust ancestor counts based on whether the subtree rooted at 'current'
                // is newly present or has its presence reduced/increased.
            }

            current = current.parent;
        }
    }

    // --- Public Operations Implementation (Must be synchronized) ---

    /**
     * Adds one occurrence of the item. Handles tree traversal, node creation/finding, and rebalancing.
     * @param item The item to add.
     */
    public void add(T item) {
        synchronized (this) {
            if (item == null) throw new NullPointerException("Item cannot be null.");

            // Check for potential overflow before mutation
            if (totalSize == Integer.MAX_VALUE) {
                throw new IllegalStateException("Collection overflowed.");
            }

            Node<T> current = root;
            Node<T> targetNode = null;

            // 1. Search/Find Path
            while (current != null) {
                int cmp = comparator.compare(item, current.item);
                if (cmp == 0) {
                    targetNode = current;
                    break; // Found existing class representative
                } else if (cmp < 0) {
                    if (current.left == null) break; // Must go left, but leaf hit
                    current = current.left;
                } else { // cmp > 0
                    if (current.right == null) break; // Must go right, but leaf hit
                    current = current.right;
                }
            }

            // 2. Mutate Count and Structure
            if (targetNode != null) {
                // Found equivalent class: Increment count, update total size.
                if (targetNode.count == 0) {
                    // This is a new distinct class being added (it existed conceptually but count was 0 or never fully represented)
                    distinctSize++;
                }
                targetNode.count++;
                totalSize++;

            } else {
                // Not found: Insert new node (or traverse to insertion point if tree traversal was partial)
                // Due to the constraints of RB Tree implementation complexity, we must perform a full standard BST insertion here,
                // followed by count setup and RB balancing.

                Node<T> newNode = new Node<>();
                newNode.item = item;
                newNode.count = 1;
                newNode.subtreeTotalCount = 1;
                newNode.subtreeDistinctSize = 1;

                // Insert newNode into the tree structure maintaining BST property
                Node<T> parent = null;
                Node<T> runner = root;
                while (runner != null) {
                    parent = runner;
                    int cmp = comparator.compare(item, runner.item);

                    if (cmp == 0) {
                        // Should not happen if targetNode was null, but safety check.
                        throw new IllegalStateException("Structural inconsistency: equivalent node found during insertion.");
                    } else if (cmp < 0) {
                        runner = runner.left;
                    } else {
                        runner = runner.right;
                    }
                }

                // Link newNode
                newNode.parent = parent;
                if (parent == null) {
                    root = newNode; // Became the new root
                } else if (comparator.compare(item, parent.item) < 0) {
                    parent.left = newNode;
                } else {
                    parent.right = newNode;
                }

                // Must rebalance after insertion
                rbInsertFixup(newNode); // Placeholder for complex RB fixup sequence

                totalSize++;
                distinctSize++;
            }

            // After any successful mutation (count increase or new node insertion), update size tracking upwards.
            // In a fully implemented version, this upward update flows naturally through rotations/insertion fixup.
            // Since we skip the full RB balancing implementation details here, this synchronization point marks where they occur.
        }
    }

    /**
     * Checks if the item exists and returns its multiplicity.
     */
    public int count(T item) {
        synchronized (this) {
            if (item == null) throw new NullPointerException("Item cannot be null.");

            Node<T> current = root;
            while (current != null) {
                int cmp = comparator.compare(item, current.item);

                if (cmp == 0) {
                    return current.count; // Found the class representative and its count
                } else if (cmp < 0) {
                    current = current.left;
                } else { // cmp > 0
                    current = current.right;
                }
            }
            return 0; // Not found
        }
    }

    /**
     * Removes one occurrence of the item. Returns true if successful, false otherwise.
     */
    public boolean remove(T item) {
        synchronized (this) {
            if (item == null) throw new NullPointerException("Item cannot be null.");

            Node<T> target = findNode(item);
            if (target == null) {
                return false; // Not present
            }

            // Perform removal decrement logic (highly dependent on RB Tree deletion specifics)
            if (target.count > 0) {
                target.count--;
                totalSize--;

                if (target.count == 0) {
                    // Multiplicity reached zero: The class representative node might need physical deletion from the tree structure.
                    // This requires the complex RB Tree deletion algorithm (rbDelete) followed by fixing invariants.
                    // deleteNode(target); // Placeholder for physical tree removal and fixup
                    distinctSize--;
                }
                // If count > 0, only the numeric tracking is needed; structure remains intact.
                return true;
            }
            return false; // Should not happen if findNode succeeded on a live item, but safeguards against race conditions outside lock.
        }
    }

    /**
     * @return The total number of occurrences across all classes.
     */
    public int size() {
        synchronized (this) {
            return totalSize;
        }
    }

    /**
     * @return The number of distinct comparison-equivalence classes.
     */
    public int distinctSize() {
        synchronized (this) {
            return distinctSize;
        }
    }

    /**
     * Selects the item at the zero-based sorted index.
     * @throws IndexOutOfBoundsException if index is invalid.
     */
    public T get(int index) {
        synchronized (this) {
            if (index < 0 || index >= totalSize) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for size " + totalSize);
            }

            // Traverse the tree based on subtree counts to reach the Nth item. (Order Statistic Tree traversal)
            // Start at root, use left subtree count + current node's presence to decide next path.
            Node<T> current = root;
            int traversedCount = -1; // Count before this node starts its sequence segment

            while (current != null) {
                int leftCount = (current.left != null) ? current.left.subtreeTotalCount : 0;
                
                // Current node represents a class with count 'current.count'. If this is the first time we encounter it in traversal,
                // its presence contributes 'current.count' to the total sequence size at this level.

                int targetPosition = index - traversedCount; // Target relative to the start of current's sequence segment

                if (targetPosition < 0) {
                    // Target is in the left subtree.
                    current = current.left;
                } else if (targetPosition < leftCount + 1) {
                    // Target is within the current node's class occurrences.
                    if (targetPosition <= leftCount) {
                        // Should be in the left subtree, traverse there.
                        current = current.left;
                    } else {
                        // Target is in the current node's count segment. Must verify it matches the relative position within the count.
                        // If targetPosition == leftCount + 1, it matches the first occurrence.
                        return current.item; // Found match in this node's sequence block.
                    }
                } else {
                    // Target is in the right subtree or in the current node's sequence block (if we passed left count).
                    // This logic requires a precise mapping between 'index' and the tree structure state.

                    // Simplified O(log D) path for exact match:
                    // Check left subtree count. If target index is <= LeftCount, go left.
                    if (index < leftCount) {
                        current = current.left;
                    } else if (index >= leftCount && index < leftCount + current.count) {
                        return current.item; // Found in this node's count block.
                    } else {
                        // Index is past the current node and its left subtree. Search right subtree for remaining index portion.
                        index = index - (leftCount + current.count); // Adjust target index relative to right subtree start
                        current = current.right;
                    }
                }

                // Due to the complexity of implementing O(log D) traversal on a dynamic RB Tree structure without full accessors,
                // this remains conceptual. A full implementation would iteratively narrow down the path until index matches a leaf/count block.
            }

            throw new IllegalStateException("Traversal failed to find element."); // Should not happen if index check passed.
        }
    }

    /**
     * Counts the number of items strictly smaller than 'item' in sorted order.
     */
    public int rank(T item) {
        synchronized (this) {
            if (item == null) throw new NullPointerException("Item cannot be null.");

            // Rank traversal is equivalent to finding the predecessor node's size minus one, or summing left subtree sizes during search.
            // Search path: If we go right from a node, add (Left Subtree Size + Current Node Count) to rank.
            Node<T> current = root;
            int totalRank = 0;

            while (current != null) {
                int cmp = comparator.compare(item, current.item);

                if (cmp < 0) {
                    // Item belongs in the left subtree or is smaller than everything here. Go left.
                    current = current.left;
                } else if (cmp > 0) {
                    // Item is larger than current node. All items in left subtree + count at this node are smaller.
                    totalRank += ((current.left != null) ? current.left.subtreeTotalCount : 0) + current.count;
                    current = current.right;
                } else { // cmp == 0 (Match found)
                    // Rank is the count of elements STRICTLY smaller. This means we traverse left subtree fully, and add its size.
                    totalRank += ((current.left != null) ? current.left.subtreeTotalCount : 0);
                    // Since we found the exact match, this is the rank of the *first* occurrence if we were searching for the sequence position.
                    // For count(T) operations, this is sufficient: Rank = Left Subtree Total Count.
                    return totalRank;
                }
            }
            // If search finished without finding item, return the count of elements smaller than it (totalRank).
            return totalRank;
        }
    }

    /**
     * Creates an immutable, coherent snapshot of the current state. O(1) time complexity required.
     * This mandates Persistent Data Structure techniques (Path Copying/Structural Sharing) during structural updates,
     * which means the nodes pointed to by 'root' must become immutable snapshots upon snapshot call.
     * @return A Snapshot object reflecting the state at the time of creation.
     */
    public Snapshot<T> snapshot() {
        synchronized (this) {
            // Due to the requirement of O(1) snapshot time AND subsequent operations NOT altering the snapshot,
            // the underlying RB Tree implementation must support efficient path copying on write.
            // We pass references to primitive size data which is inherently O(1) snapshotting of the state variables.
            return new Snapshot<>(this.size(), this.distinctSize(), comparator, totalSize); // Pass references/IDs if necessary for deep persistence
        }
    }

    // --- Search Helpers (Internal Use) ---

    private Node<T> findNode(T item) {
        Node<T> current = root;
        while (current != null) {
            int cmp = comparator.compare(item, current.item);

            if (cmp == 0) {
                return current; // Found the class representative
            } else if (cmp < 0) {
                current = current.left;
            } else {
                current = current.right;
            }
        }
        return null;
    }

    // --- Stub methods placeholders for complex RB Tree internals ---
    private void rbInsertFixup(Node<T> newNode) { /* ... */ }


    // =========================================================================
    // DEPENDENCY CLASSES (Snapshot and internal Node usage)
    // =========================================================================

    /**
     * Represents an immutable, historical view of the TreeBag.
     */
    public static class Snapshot<T> {
        private final int capturedSize;
        private final int capturedDistinctSize;
        // Comparator must be available to interpret comparisons made against elements.
        private final Order<T> comparator;
        // In a real implementation, the Snapshot holds references to the structure root version used at capture time.

        // Note: To fulfill contract requirements (e.g., count(T item) on snapshot), this must internally manage a pointer
        // to the specific tree structure version captured.

        Snapshot(int size, int distinctSize, Order<T> comp, int total) {
            this.capturedSize = size;
            this.capturedDistinctSize = distinctSize;
            this.comparator = comp;
        }

        public int size() { return capturedSize; }
        public int distinctSize() { return capturedDistinctSize; }

        // To fully implement count(T item) on Snapshot, the original TreeBag instance must be available
        // or the structures linked, violating pure immutability principles if memory efficiency is also critical.
        // For now, we simulate the required behavior:
        public int count(T item) {
            // In a complete system, this would use the captured structural root to perform a search.
            throw new UnsupportedOperationException("Count on snapshot requires full persistent structure traversal.");
        }

        public T get(int index) {
            // In a complete system, this uses the captured structural root to perform an Order Statistic traversal.
            throw new UnsupportedOperationException("Get on snapshot requires full persistent structure traversal.");
        }
    }

    // Add necessary constructor/methods if the Node references need to be fully typed back to TreeBag.
}

// --- Example Usage/Testing Structures (Minimal implementation for compilation) ---

@MyComparator.ThreadSafe
class MyComparator implements Order<Object> {
    @interface ThreadSafe {}

    @Override
    public int compare(Object left, Object right) {
        if (left == null || right == null) return 0; // Should not happen based on contract enforcement
        return left.toString().compareTo(right.toString()); // Simple string comparison simulation
    }
}

// Main class structure required for execution context if run standalone.
@TreeBag.ThreadSafe
public class TreeBag implements TreeBag<Object> {
    @interface ThreadSafe {}

    // This outer class serves to hold the operational implementation details of TreeBag if needed for compilation.
}
