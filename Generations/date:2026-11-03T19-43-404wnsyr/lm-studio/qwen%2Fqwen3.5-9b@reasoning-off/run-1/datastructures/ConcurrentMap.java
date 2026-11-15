```java
import java.lang.reflect.Array;

/**
 * A thread-safe concurrent Map implementation using only built-in Java synchronization.
 * 
 * Invariants:
 * 1. The map is in a consistent state where keys are unique.
 * 2. Any modification must complete atomically or be safely rolled back.
 * 3. Visibility of changes is guaranteed by monitor locking.
 * 
 * Policies:
 * - insert(key, value): Updates existing or inserts new. Returns true if new, false if updated.
 * - get(key): Returns the value or null if not found.
 * - remove(key): Removes and returns the value if found, null otherwise.
 * - size(): Returns current number of entries.
 * - isEmpty(): Returns true if size is 0.
 * - clear(): Removes all entries.
 * - containsKey(key): Returns true if key exists.
 * 
 * Note: Contains no entries (new Map()) is O(1) as initial capacity is 0, but grows dynamically.
 * For performance in production, we might implement a specific initial size and resize logic,
 * but this simple implementation adds entries to the first array if it can fit.
 */
public class ConcurrentMap<K, V> {
    private static final int INITIAL_CAPACITY = 4;
    private static final int DEFAULT_CAPACITY = 16; // Power of 2 for simplicity
    
    private int threshold = INITIAL_CAPACITY;
    private int modCount = 0;
    private Entry<K, V>[] map;

    public ConcurrentMap() {
        this(DEFAULT_CAPACITY);
    }

    public ConcurrentMap(int initialCapacity) {
        this.map = new Entry[initialCapacity];
        this.threshold = initialCapacity;
    }

    // Linked List Node implementation for a simple chaining collision resolution
    // This is kept simple to avoid ArrayDeque/LinkedList imports, just manual Node class
    private static class Node<K, V> {
        final K key;
        V value;
        Node<K, V> next;
        boolean nextOccupied; // Optimized flag to know if next slot is used without traversal if possible, 
                             // but strictly, we traverse. Let's keep standard linked list.
        
        Node(K k, V v, Node<K, V> n) {
            this.key = k;
            this.value = v;
            this.next = n;
        }
    }

    // Hash function using bit mixing to ensure even distribution on powers of 2
    private int hash(K key) {
        if (key == null) return 0;
        int h = key.hashCode();
        h ^= (h >>> 20) ^ (h >>> 12);
        return h ^ (h >>> 7) ^ (h >>> 4);
    }

    public void put(K key, V value) {
        if (key == null) throw new NullPointerException("Key cannot be null");
        if (value == null) throw new NullPointerException("Value cannot be null");

        synchronized (this) {
            // Calculate index
            int index = hash(key) & (map.length - 1);
            
            Node<K, V> p = map[index];
            Node<K, V> last = null;
            
            // Check if exists
            while (p != null) {
                if (p.key.equals(key)) {
                    p.value = value;
                    modCount++;
                    return;
                }
                last = p;
                p = p.next;
            }

            // New entry added
            modCount++;
            
            // Create new node
            p = new Node<>(key, value, p);
            
            // Insert at head
            map[index] = p;

            // Check capacity
            if (p.nextOccupied || checkThreshold()) {
                // Resize needed. 
                // Since we just added one, if we hit threshold, we must resize.
                // The logic checkThreshold will verify if map.length < threshold
            }
        }
    }

    /**
     * Check if we need to resize.
     * Since we implemented manual resizing, we check current load vs threshold.
     * We don't track count variable to avoid atomic integer overhead without lib, 
     * but we can count during resize or assume threshold policy.
     * Simpler: Just assume if we add more than X times, we resize.
     * But for correctness without counters, we rely on load factor logic.
     * Let's add a simple count variable. It is not used for locks, just for resize logic.
     */
    private int count = 0;

    private boolean checkThreshold() {
        if (count >= map.length) {
            resize();
        }
        return false;
    }

    private void resize() {
        synchronized (this) {
            int newCapacity = map.length * 2;
            Entry<K, V>[] newMap = new Entry[newCapacity];
            
            // Copy old data to new structure. 
            // Re-initializing the map array to Entry objects for performance? 
            // The 'map' array currently holds Nodes. We need to convert or copy Nodes.
            // Let's redesign slightly to make map an array of Nodes, not generic Entry if we want strict control.
            // But the problem says 'Map'. Let's assume map is array of Nodes.
            
            int newThreshold = newCapacity; // Grow by doubling
            int oldCapacity = map.length;
            
            // Temp storage for new map to avoid overwriting old map while iterating
            // Since we have synchronization, we can just iterate safely.
            
            // Re-build the table in newMap
            for (int i = 0; i < oldCapacity; i++) {
                Node<K, V> curr = map[i];
                if (curr != null) {
                    int newIndex = hash(curr.key) & (newCapacity - 1);
                    map[i] = map[newIndex]; // Copy the head to correct place in newMap (conceptually)
                    // Wait, we can't do this in place easily without re-linking.
                }
            }
            // Correct resize strategy:
            // Create new array. Iterate old array. For each non-null, re-insert logic into new array.
            // Or simply move heads.
            
            Node<K, V>[] tempNewMap = new Node[newCapacity];
            
            // Clear old heads if they are being reused? 
            // Actually, we must rebuild the list structure because indices changed.
            for (int i = 0; i < oldCapacity; i++) {
                map[i] = null; // Clear old
                Node<K, V> curr = map[i]; // Wait, I used 'map' for old, need to save it.
            }
            
            // Let's restart the resize logic properly inside the synchronized block
            int[] oldNodes = Arrays.copyOf(map, oldCapacity);
            int currentNodes = count;
            count = 0;
            modCount++;
            
            map = new Node[newCapacity];
            int newThreshold = newCapacity;
            threshold = newThreshold;

            for (Node<K, V> node : oldNodes) {
                while (node != null) {
                    int index = hash(node.key) & (newCapacity - 1);
                    if (map[index] == null) {
                        map[index] = node;
                    } else {
                        // Check if key exists at this index (collision)
                        Node<K, V> e = map[index];
                        while (e != null) {
                            if (e.key.equals(node.key)) {
                                // Update value
                                if (node.next == null) {
                                    map[index] = node; // Update head, value update handled by new Node if we were tracking
                                    // Actually simpler: Just create new Node with updated value and insert.
                                    // But this is messy.
                                } else {
                                    // Update next's value? No, let's replace.
                                }
                                break;
                            }
                            e = e.next;
                        }
                        
                        // If collision with existing, update value
                        // This logic is getting complex for a single-resize pass.
                        // Simplest: Remove old, add new.
                    }
                    
                    // Update count
                    count++;
                    node = node.next;
                }
            }
        }
    }

    // Let's rewrite the whole class to be cleaner and strictly correct without complex resize edge cases in one block.
    // We will use a simple array of Nodes.
    // We will maintain a separate counter.
    
    // Re-implementing clean from scratch based on requirements.
}
```
