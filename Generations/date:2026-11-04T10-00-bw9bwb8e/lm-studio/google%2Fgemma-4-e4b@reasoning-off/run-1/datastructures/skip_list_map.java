@SkipListMap.ThreadSafe
class SkipListMap implements Comparable<SkipListMap> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 16;
    private Node head;
    private int currentLevel;

    // Simple internal structure to simulate the map, mapping keys (Integers) to values (Booleans)
    // For simplicity in this constrained environment, we model it as a set/map where keys are present.
    // The values stored will be simple markers (true in this case).

    private static class Node {
        final int key;
        volatile boolean present; // Tracks if this node is logically part of the map (for deletion marking)
        Node[] forward; // Array of pointers to next nodes at different levels

        Node(int key, int level) {
            this.key = key;
            this.forward = new Node[level + 1];
            this.present = true; // Initially present upon creation during insertion process
        }
    }

    public SkipListMap() {
        // Initialize with a dummy head node. Key is irrelevant for the head.
        this.head = new Node(Integer.MIN_VALUE, MAX_LEVEL); // Dummy head
        this.currentLevel = 0;
    }

    /**
     * Simulates insertion into the map. If the key exists, it does nothing (idempotent).
     * This operation is thread-safe.
     * @param key The integer key to insert.
     * @return true if the key was newly inserted, false if it already existed.
     */
    public synchronized boolean put(int key) {
        // In a real concurrent skip list, insertion involves finding predecessors and then CAS operations.
        // Given the strict limitations (no atomic primitives, only monitors/volatile),
        // we must acquire a full lock around the necessary traversal and modification phases
        // to guarantee consistency without complex fine-grained locking structures.

        Node newNode = null;
        Node[] update = new Node[MAX_LEVEL + 1]; // Array to store predecessors found during search

        // Phase 1: Search for the correct insertion point and check existence
        Node current = head;
        int traversedLevel = -1; // Tracks the highest level reached during traversal

        for (int i = currentLevel; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].key < key) {
                current = current.forward[i];
            }
            update[i] = current; // Record the predecessor at level i
        }

        // Move to the potential location at level 0
        current = current.forward[0];

        // Check if the key already exists (Search Phase completion)
        if (current != null && current.key == key) {
            return false; // Key already present
        }

        // If we reached here, the key does not exist yet. We proceed to insert.

        // Phase 2: Insertion (Only possible if no other thread interferes during the traversal cleanup)
        // Since we held synchronization implicitly via 'synchronized' on the method, 
        // we now simulate the probabilistic level assignment and linking.

        // --- Simplified Level Generation for Demonstration ---
        int newLevel = 0;
        while (newLevel < MAX_LEVEL && Math.random() < 0.5) {
            newLevel++;
        }

        newNode = new Node(key, newLevel);

        // Update pointers in the structure
        Node p = head;
        for (int i = currentLevel; i >= 0 && i < newLevel; i--) {
            // We need to ensure that 'update[i]' is actually the correct predecessor found in Phase 1.
            // Because we are holding the main lock, we reuse the update array populated during search.

            while (p.forward[i] != null && p.forward[i].key < key) {
                p = p.forward[i];
            }
            // Now, p is the predecessor at level i (or head if at root)
        }

        // Re-traversal to correctly stitch in the new node while holding the lock:
        Node currentPred = head;
        for (int i = currentLevel; i >= 0; i--) {
            while (currentPred.forward[i] != null && currentPred.forward[i].key < key) {
                currentPred = currentPred.forward[i];
            }
            // If this level is within the new node's height, link it.
            if (i <= newLevel) {
                // The 'update' array approach is cleaner for insertion during search, 
                // but since we moved to a synchronized block simulation, we simplify linking:

                if (i <= newLevel) {
                    // Link newNode after predecessor found at level i.
                    // For simplicity, we use the head as the starting point for all linkages here 
                    // because precise predecessor tracking during lock acquisition requires careful state management.

                    Node pred = head;
                    while (pred.forward[i] != null && pred.forward[i].key < key) {
                        pred = pred.forward[i];
                    }

                    newNode.forward[i] = pred.forward[i];
                    pred.forward[i] = newNode;

                    if (i > currentLevel) {
                        // If we inserted above the previous max level, update it.
                        currentLevel = i;
                    }
                }
            }
        }

        // Note: The complexity of implementing a lock-free/optimistically locked structure 
        // using only basic monitors is extremely high. By wrapping the entire find-then-modify sequence 
        // in 'synchronized', we achieve thread safety while fulfilling the monitor usage constraint.

        return true;
    }

    /**
     * Searches for the key. This operation reads structure pointers but does not modify them, 
     * so it can potentially operate under a relaxed synchronization model if the readers wait for writers.
     * However, to guarantee visibility of changes (especially level updates), we synchronize reads too, 
     * or rely on the volatile reads for `forward` pointers if insertions are complete. Given the constraints,
     * we synchronize to ensure visibility of all pointer modifications during insertion.
     * @param key The integer key to search for.
     * @return true if the key is present, false otherwise.
     */
    public boolean contains(int key) {
        // Read operations must also observe the most recent structural changes made by writers.
        synchronized (this) {
            Node current = head;

            for (int i = currentLevel; i >= 0; i--) {
                while (current.forward[i] != null && current.forward[i].key < key) {
                    current = current.forward[i];
                }
            }

            // Move to potential match at level 0
            current = current.forward[0];

            if (current != null && current.key == key) {
                return true;
            }
            return false;
        }
    }

    /**
     * Removes the key if present. This operation is thread-safe.
     * @param key The integer key to remove.
     * @return true if the key was found and removed, false otherwise.
     */
    public synchronized boolean remove(int key) {
        // Search phase (identical to contains, finding predecessors up to level 0)
        Node current = head;
        Node[] update = new Node[MAX_LEVEL + 1]; // To store predecessors for unlinking

        for (int i = currentLevel; i >= 0; i--) {
            while (current.forward[i] != null && current.forward[i].key < key) {
                current = current.forward[i];
            }
            update[i] = current;
        }

        current = current.forward[0]; // Potential match found

        if (current == null || current.key != key) {
            return false; // Not found
        }

        // Unlinking phase: Traverse from the highest current level down to 0, adjusting forward pointers
        for (int i = 0; i <= currentLevel; i++) {
            // Check if the predecessor at this level points to the node being removed (current)
            if (update[i].forward[i] == current) {
                // Bypass the node
                update[i].forward[i] = current.forward[i];
            }
        }

        // Decrease overall level if necessary (garbage collection of highest levels)
        while (currentLevel > 0 && head.forward[currentLevel] == null) {
            currentLevel--;
        }

        return true;
    }


    // --- Dummy Comparable implementation required by the template, not functionally used for Map operations ---
    @Override
    public int compareTo(SkipListMap other) {
        throw new UnsupportedOperationException("Comparison not defined for SkipListMap instances.");
    }

    // --- Boilerplate main method to satisfy the requirement of being a self-contained file ---
    public static void main(String[] args) {
        // Example usage verification (not part of the concurrency primitive itself, but useful for testing)
        SkipListMap map = new SkipListMap();

        System.out.println("Putting 10: " + map.put(10)); // Should be true
        System.out.println("Putting 20: " + map.put(20)); // Should be true
        System.out.println("Contains 10: " + map.contains(10)); // Should be true
        System.out.println("Putting 10 again: " + map.put(10)); // Should be false
        System.out.println("Removing 10: " + map.remove(10)); // Should be true
        System.out.println("Contains 10 after removal: " + map.contains(10)); // Should be false
    }
}
