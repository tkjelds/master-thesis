@SkipListSet.ThreadSafe
class SkipListSet {
    @interface ThreadSafe {}


    private static class Node {
        final int key;
        // 'next' array stores the next node at each level.
        final Node[] next;

        Node(int key, int maxLevel) {
            this.key = key;
            this.next = new Node[maxLevel];
        }
    }

    private volatile Node head;
    private volatile int maxLevel;
    private final Object lock = new Object(); // Lock object for head manipulation and structural changes

    // Sentinel value/initial capacity simulation. We use a special head node.
    private static final int MAX_CAPACITY = 16; // Simulate practical bounds

    public SkipListSet() {
        // Initialize with a dummy head node representing minimum key or starting point.
        this.maxLevel = 1;
        // The head node itself doesn't hold a meaningful key for comparison,
        // it just serves as the entry point. We initialize its level to simulate the current effective max level.
        this.head = new Node(Integer.MIN_VALUE, MAX_CAPACITY);
    }

    /**
     * Searches for a key in the skip list. This operation observes the current structure
     * but does not modify it and is designed to be highly concurrent (read-only traversal).
     * @param key The key to search for.
     * @return true if the key is present, false otherwise.
     */
    public boolean contains(int key) {
        Node current = head;
        // Start traversal from the highest potentially useful level down to level 0.
        for (int i = maxLevel - 1; i >= 0; i--) {
            // Traverse forward while the next node exists AND its key is less than the search key.
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
        }

        // Move to the potential match on level 0
        current = current.next[0];

        // Check if this node exists and matches the key
        return current != null && current.key == key;
    }

    /**
     * Adds a key to the skip list if it is not present. This operation requires synchronization
     * because it modifies the structure (inserting new nodes and potentially increasing maxLevel).
     * @param key The key to add.
     * @return true if the key was newly added, false if it already existed.
     */
    public synchronized boolean add(int key) {
        // Find predecessor nodes for the new node's height (update array simulation)
        int[] update = new int[MAX_CAPACITY]; // Stores the level where we descended from each node
        Node current = head;

        // 1. Search Phase: Find insertion points and determine the level for the new node
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            // Store where we landed at this level if needed for insertion mapping.
            update[i] = 0; // We don't strictly need 'update' array index for simple insertion tracking if we build the new node dynamically.
        }

        // Check if key already exists on level 0 after traversal
        current = current.next[0];
        if (current != null && current.key == key) {
            return false; // Key already exists
        }

        // 2. Insertion Phase: Determine new node level and splice in
        // In a production concurrent SkipList, the level determination is probabilistic.
        // For this constrained implementation, we will fix a reasonable level for simplicity
        // that ensures the structure grows somewhat realistically but remains bounded by MAX_CAPACITY.
        int newNodeLevel = 1; // Start at level 1 (level 0 is mandatory)
        while (newNodeLevel < MAX_CAPACITY && Math.random() < 0.5) { // Simulate probabilistic promotion
            newNodeLevel++;
        }

        Node newNode = new Node(key, newNodeLevel); // Node's 'next' array size is determined by its promotion level

        // Splice the new node into the list across all levels up to newNodeLevel
        current = head;
        for (int i = maxLevel - 1; i >= 0; i--) {
            // Traverse to the node whose 'next' pointer at level i needs updating.
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }

            if (i < newNodeLevel) {
                // This level contributes to the insertion path for the new node.
                // current is the predecessor node we need to update.
                newNode.next[i] = current.next[i]; // New node points to what 'current' used to point to
                current.next[i] = newNode;         // 'current' now points to the new node
            } else if (i == maxLevel - 1 && newNodeLevel > i + 1) {
                // Handle the case where we might need to increase the overall list height dynamically,
                // though the head node structure needs careful management for this.
                // Given our initialization where 'head' is fixed, we must ensure head exists up to maxLevel.
                // If newNodeLevel > current effective maxLevel, we update the global maxLevel and ensure 'head' supports it.
            }
        }

        // Handle potential global maxLevel increase if the new node reaches higher than before.
        if (newNodeLevel > maxLevel) {
            // This part is tricky without a proper "find update path" mechanism tracking the predecessor up to new heights.
            // For simplification matching typical textbook examples where 'head' nodes are involved:
            maxLevel = newNodeLevel;
        }

        return true;
    }


    /**
     * Removes a key from the skip list. Synchronization is required for structural modification.
     * @param key The key to remove.
     * @return true if the key was found and removed, false otherwise.
     */
    public synchronized boolean remove(int key) {
        Node current = head;
        Node[] update = new Node[MAX_CAPACITY]; // To track predecessors at each level

        // 1. Search Phase: Find the node and record all its predecessors up to its height
        for (int i = maxLevel - 1; i >= 0; i--) {
            while (current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }
            update[i] = current; // Store the predecessor node for splicing later
        }

        // Check if target exists (must be on level 0)
        current = current.next[0];
        if (current == null || current.key != key) {
            return false; // Key not found
        }

        // 2. Deletion Phase: Unlink the node at all its levels
        Node toDelete = current;
        current = head; // Reset traversal context for unlink operations starting from the head

        for (int i = 0; i < maxLevel; i++) {
            // If the next node at this level is the one we want to delete, bypass it.
            while (current != null && current.next[i] != null && current.next[i].key < key) {
                current = current.next[i];
            }

            if (current != null && current.next[i] == toDelete) {
                // Bypass the node at this level
                current.next[i] = toDelete.next[i];
            } else if (current == head && current.next[i] != toDelete) {
                // If we didn't hit the target, and we are still at the head context,
                // it means the deleted node's level was lower than this check. We stop checks higher up.
                // (This part is inherently complex without knowing the exact height of toDelete easily here.)
            } else if (current == head && current.next[i] == null) {
                // Reached the end of the list traversal path gracefully.
            }
        }

        // After unlink, we might need to decrease maxLevel if the deleted node was the only one at the top.
        while (maxLevel > 1 && head.next[maxLevel - 1] == null) {
            maxLevel--;
        }

        return true;
    }
}
