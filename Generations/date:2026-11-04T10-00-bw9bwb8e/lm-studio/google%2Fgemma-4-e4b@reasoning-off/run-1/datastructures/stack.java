@ConcurrentStack.ThreadSafe
class ConcurrentStack {
    @interface ThreadSafe {}

    private Object[] stack;
    private int top;
    private final int capacity;

    public ConcurrentStack(int capacity) {
        this.capacity = capacity;
        // In a constrained environment where arrays are the only container, 
        // we simulate the structure here. The array size will be capacity + 1 if we are using it to store elements directly, 
        // but since we are mimicking a stack implementation with fixed capacity management, using `capacity` as the effective limit is safer.
        // We initialize with size capacity and use 'top' pointer which ranges from 0 to capacity-1 (if we consider indices).
        // If capacity is N, array size should be N.
        this.stack = new Object[capacity];
        this.top = -1; // Stack is empty when top is -1
    }

    /**
     * Pushes an item onto the stack. Blocks if the stack is full until space becomes available.
     * @param item The item to push.
     */
    public void push(Object item) throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is full
            while (top >= capacity - 1) {
                wait();
            }

            // Push operation
            top++;
            stack[top] = item;

            // Notify waiting threads (if any are trying to pop)
            notifyAll();
        }
    }

    /**
     * Pops an item from the stack. Blocks if the stack is empty until an item is available.
     * @return The object popped from the stack.
     * @throws InterruptedException If the wait is interrupted.
     */
    @SuppressWarnings("unchecked")
    public Object pop() throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is empty
            while (top == -1) {
                wait();
            }

            // Pop operation
            Object item = stack[top];
            stack[top] = null; // Help garbage collection
            top--;

            // Notify waiting threads (if any are trying to push)
            notifyAll();

            return item;
        }
    }

    /**
     * Optional: Peek at the top element without removing it. Blocks if the stack is empty.
     * @return The object at the top of the stack.
     * @throws InterruptedException If the wait is interrupted.
     */
    @SuppressWarnings("unchecked")
    public Object peek() throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is empty
            while (top == -1) {
                wait();
            }

            return stack[top];
        }
    }
}
