@ConcurrentStack.ThreadSafe
public class ConcurrentStack {
    @interface ThreadSafe {}


    private Object[] stackArray;
    private int top; // Points to the next available slot, or size if full/empty depending on convention.
    private final int capacity;

    /**
     * Constructs a ConcurrentStack with the given capacity.
     * @param capacity The maximum number of items this stack can hold. Must be greater than 0.
     * @throws IllegalArgumentException if capacity is less than or equal to 0.
     */
    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }
        this.capacity = capacity;
        // We allocate size + 1 because we might use 'top' to track the next insertion point,
        // allowing us to represent size directly with top value in some implementations.
        // For simplicity matching standard array-based implementation where size is 'top', we use capacity directly.
        this.stackArray = new Object[capacity];
        this.top = 0; // 'top' tracks the current number of elements in the stack.
    }

    /**
     * Pushes an item onto the stack. If the stack is full, this method blocks until space becomes available.
     * @param item The item to push onto the stack.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     */
    public void push(Object item) throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is full (i.e., size equals capacity)
            while (top == capacity) {
                wait();
            }

            // Push operation: insert item and increment count
            stackArray[top] = item;
            top++;

            // Notify waiting consumers (popers) that an item is now available
            notifyAll();
        }
    }

    /**
     * Pops an item from the stack. If the stack is empty, this method blocks until an item becomes available.
     * @return The object popped from the stack.
     * @throws InterruptedException if the current thread is interrupted while waiting.
     * @throws IllegalStateException if the stack was somehow structurally inconsistent (should not happen in normal operation).
     */
    public Object pop() throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is empty (top == 0)
            while (top == 0) {
                wait();
            }

            // Pop operation: retrieve item and decrement count
            top--;
            Object item = stackArray[top];

            // Optional: Help garbage collection by nulling out the reference
            stackArray[top] = null;

            // Notify waiting producers (pushers) that space is now available
            notifyAll();
            return item;
        }
    }

    /**
     * Checks if the stack is currently empty. This operation does not block.
     * @return true if the stack contains no elements, false otherwise.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return top == 0;
        }
    }

    /**
     * Returns the current number of elements in the stack. This operation does not block.
     * @return The current size of the stack.
     */
    public int size() {
        synchronized (this) {
            return top;
        }
    }

    /*
     * Note on Array Indexing:
     * Using 'top' initialized to 0, and incrementing/decrementing it from [0, capacity-1],
     * allows us to use 'top' as the count of elements (size).
     * push: item goes into stackArray[top], then top++. Size increases.
     * pop: top-- (to point to the item), retrieve stackArray[top]. Size decreases.
     * This implementation uses 0-based indexing for array access matching the count variable 'top'.
     * If capacity=3, indices used are 0, 1, 2. 'top' ranges from 0 (empty) to 3 (full).
     * push increments 'top' AFTER insertion. pop decrements 'top' BEFORE retrieval.
     */
}
