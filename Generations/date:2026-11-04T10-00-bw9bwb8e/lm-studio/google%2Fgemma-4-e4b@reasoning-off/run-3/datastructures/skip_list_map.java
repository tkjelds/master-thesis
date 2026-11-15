@SkipListMap.ThreadSafe
class SkipListMap {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 16;

    /**
     * Node class for the Skip List.
     */
    private static class Node {
        final int key;
        int value; // Storing value directly for simplicity, assuming mapping from key to a single state/value.
        Node[] next; // Array of pointers to the next node at each level

        Node(int key, int value, int level) {
            this.key = key;
            this.value = value;
            next = new Node[level + 1]; // Levels are usually 0 to level (inclusive)
        }
    }

    private Node head;
    private int currentMaxLevel;
    private final Object lock = new Object(); // Lock object

    public SkipListMap() {
        // Initialize with a sentinel head node. Key and value are irrelevant for the head.
        head = new Node(Integer.MIN_VALUE, 0, MAX_LEVEL - 1);
        currentMaxLevel = 0;
    }

    /**
     * Simulates putting a key-value pair into the map. If the key exists, its value is updated.
     * This implementation uses a simplified insertion assuming successful traversal path finding, 
     * simulating necessary synchronization for correctness.
     * @param key The key to insert/update.
     * @param value The value associated with the key.
     */
    public void put(int key, int value) {
        synchronized (lock) {
            // In a real concurrent Skip List, insertion requires finding predecessors at multiple levels 
            // and atomically updating pointers. Since we are simulating concurrency control using a single lock, 
            // we perform the operation sequentially under this monitor.

            int level = getRandomLevel(); // Determine height for the new node
            Node newNode = new Node(key, value, level);

            // 1. Find insertion point predecessors (like in search)
            Node current = head;
            Node[] update = new Node[MAX_LEVEL + 1]; // Stores pointers to predecessors for insertion

            for (int i = currentMaxLevel; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].key < key) {
                    current = current.next[i];
                }
                update[i] = current; // Record predecessor at this level
            }

            // Move to the actual insertion point (level 0 successor)
            current = current.next[0];

            // Check if key already exists (Update scenario)
            if (current != null && current.key == key) {
                // Key found, update value
                current.value = value;
                return;
            }

            // Key does not exist, proceed with insertion (Insert scenario)
            if (level + 1 > currentMaxLevel + 1) {
                // If the new node is taller than current map height, update pointers from head up to level.
                // Note: We must handle the head node's next pointers correctly here.
                for (int i = currentMaxLevel + 1; i <= level; i++) {
                    update[i] = head; // Head is the predecessor for new levels
                }
                currentMaxLevel = level;
            }

            // Link the new node into the list from its appropriate level up to level 'level'
            for (int i = 0; i <= level; i++) {
                newNode.next[i] = update[i].next[i];
                update[i].next[i] = newNode;
            }
        }
    }

    /**
     * Searches for a key and returns its associated value, if present.
     * @param key The key to search for.
     * @return The value if the key is found; otherwise, returns a default indication (e.g., Integer.MIN_VALUE).
     */
    public int get(int key) {
        synchronized (lock) {
            Node current = head;

            for (int i = currentMaxLevel; i >= 0; i--) {
                while (current.next[i] != null && current.next[i].key < key) {
                    current = current.next[i];
                }
            }

            // Move to the potential location at level 0
            current = current.next[0];

            if (current != null && current.key == key) {
                return current.value;
            } else {
                // Key not found
                return Integer.MIN_VALUE; // Sentinel indicating absence
            }
        }
    }

    /**
     * Helper to simulate probabilistic level promotion.
     */
    private int getRandomLevel() {
        int lvl = 0;
        // Coin toss simulation: typically P=0.5 chance to go up a level
        while (lvl < MAX_LEVEL - 1 && Math.random() < 0.5) {
            lvl++;
        }
        return lvl;
    }

    // Example usage main method to satisfy the self-contained file requirement, though testing is omitted.
    /*
    public static void main(String[] args) {
        SkipListMap map = new SkipListMap();

        Runnable writer1 = () -> {
            map.put(10, 100);
            try { Thread.sleep(10); } catch (InterruptedException e) {}
            map.put(20, 200);
        };

        Runnable reader1 = () -> {
            while (true) {
                int val = map.get(10);
                if (val != Integer.MIN_VALUE) {
                    System.out.println("Reader saw 10: " + val);
                } else {
                    // Busy wait simulation for observation, but discouraged in real use
                    try { Thread.sleep(5); } catch (InterruptedException e) {}
                }
            }
        };

        Thread t1 = new Thread(writer1);
        Thread t2 = new Thread(reader1);

        t1.start();
        t2.start();
    }
    */
}
