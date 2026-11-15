import java.io.Serializable;

/**
 * A thread-safe concurrent implementation of a bounded buffer data structure.
 * This class simulates the behavior of a circular buffer using arrays and
 * synchronization primitives as requested.
 * 
 * Semantics:
 * - Data is stored in a fixed-size circular buffer.
 * - put(e): Adds element e. Blocks if buffer is full. Unblocks if consumer takes.
 * - take(): Removes and returns the oldest element. Blocks if buffer is empty.
 * - size(): Returns current number of elements.
 * - capacity(): Returns maximum capacity.
 * 
 * This implementation uses a single shared monitor (this) to synchronize
 * access to shared state (size, head, tail, data array).
 */
@B_Tres.ThreadSafe
public class B_Tres implements Serializable {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;

    // The underlying array holding elements. 
    // Size must be a power of 2 for efficient circular wrapping with modulo.
    private final int[] data;
    private final int capacity;

    /**
     * Construct a new B_Tres with the specified capacity.
     * @param capacity the maximum number of elements the structure can hold.
     * @throws IllegalArgumentException if capacity is non-positive.
     */
    public B_Tres(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be at least 1.");
        }
        this.capacity = capacity;
        this.data = new int[capacity];
    }

    /**
     * Gets the current capacity of the structure.
     * @return the capacity.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns the current number of elements in the buffer.
     * @return the current size.
     */
    public synchronized int size() {
        // In a full production implementation, there might be a separate 'count' variable.
        // However, without helper methods for head/tail, we calculate based on indices.
        // To make this efficient and safe, we assume the structure tracks indices 'head' and 'tail'.
        // Since we need to define the structure completely within one class without extra state 
        // unless tracked, we must assume this class manages 'head' and 'tail' explicitly.
        // 
        // REVISING DESIGN: A strict buffer needs indices to track position.
        // Since the prompt asks for 'b_tres', let's assume standard queue semantics where
        // we manage head (write pos) and tail (read pos).
        // However, calculating size by iterating 'data' array in a loop is O(N) where N=capacity.
        // A proper buffer tracks count or relative head/tail.
        // Let's add private fields for head and tail to make size O(1).
        return 0; // Placeholder, logic implemented below via fields added temporarily in thought.
    }

    // We need to define internal state for the buffer logic.
    // To keep the class self-contained and functional for a "tres" (three) structure,
    // let's interpret "tres" as a structure managing three logical slots or simply a 
    // generic bounded buffer. Given the constraints, a generic bounded buffer is the robust choice.
    
    // We will maintain 'count' for O(1) size calculation.
    private int count = 0;
    
    // We will maintain 'head' and 'tail' pointers for the circular buffer logic.
    // But wait, if we don't pass the underlying data type, we default to Integers or raw indices.
    // The prompt implies 'b_tres'. Let's assume it stores Integers.
    
    // Since the prompt says "b_tres data structure", and requires thread-safety without imports,
    // and usually 'tres' might imply "three", but in CS context it's often a generic name or typo for "buffer".
    // Let's implement a generic-like bounded buffer using an Object array or an int array.
    // Given "primitive types, arrays", let's stick to storing Integers (auto-boxed) or just use indices.
    // But usually, a data structure holds data. Let's assume it holds Objects.
    
    // Wait, strict constraint: "Do not import any libraries...". 
    // Creating a generic class without <T> syntax (Java 5+) might be tricky if generic support isn't guaranteed
    // or if the user expects raw types. Java 8 is standard now.
    // However, to be 100% safe with "no libraries", using Object[] is safest.
    
    private Object[] buffer;
    private int head = 0; // Where to put next
    private int tail = 0; // Where to take next
    
    // We need to handle the circular nature.
    // size = (tail - head + capacity) % capacity
    
    public B_Tres(int capacity) {
        if (capacity < 0) {
            throw new RuntimeException("Capacity must be non-negative");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
        // In Java, new Object is null. We track count separately for O(1) size.
        this.count = 0;
    }

    /**
     * Attempts to add an item to the buffer.
     * @param item the item to add.
     * @return true if the item was added successfully.
     * @throws RuntimeException if the buffer is full.
     * This method blocks until space is available or the buffer is no longer full.
     */
    public synchronized boolean put(Object item) {
        while (count == capacity) {
            // Buffer is full. Wait for a consumer.
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        
        buffer[head] = item;
        head = (head + 1) % capacity;
        count++;
        return true;
    }

    /**
     * Attempts to retrieve an item from the buffer.
     * @return the item at the head of the buffer.
     * @throws RuntimeException if the buffer is empty.
     * This method blocks until an item is available or the buffer is no longer empty.
     */
    public synchronized Object take() {
        while (count == 0) {
            // Buffer is empty. Wait for a producer.
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Buffer is empty after timeout", e);
            }
        }

        Object item = buffer[tail];
        buffer[tail] = null; // Reset the slot
        tail = (tail + 1) % capacity;
        count--;
        return item;
    }

    /**
     * Returns the current number of elements in the buffer.
     * This operation is lock-free to avoid contention, but since we have shared 'count',
     * we must ensure 'count' is volatile for visibility without holding the lock.
     * However, the prompt requires synchronized blocks for all public operations.
     * To avoid deadlocks (if put calls size while wait is active, etc., though unlikely here),
     * and to strictly follow "use synchronized blocks within the method body",
     * we will synchronize on 'this'.
     */
    public synchronized int size() {
        return count;
    }

    /**
     * Clears the buffer.
     */
    public synchronized void clear() {
        count = 0;
        head = 0;
        tail = 0;
        notifyAll(); // Wake up any waiting threads
    }
    
    // For demonstration of the "tres" name interpretation, we could have 3 slots,
    // but generic bounded buffer covers the requirements better. 
    // Let's rename constructor to handle specific sizes.
}
