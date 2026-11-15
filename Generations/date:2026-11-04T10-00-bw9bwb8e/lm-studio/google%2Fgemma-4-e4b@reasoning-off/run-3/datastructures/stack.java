@ConcurrentStack.ThreadSafe
class ConcurrentStack {
    @interface ThreadSafe {}

    private Object[] stack;
    private int top;
    private final int capacity;

    // Constructor for a stack with fixed capacity
    public ConcurrentStack(int capacity) {
        this.capacity = capacity;
        this.stack = new Object[capacity];
        this.top = -1; // Stack is empty initially
    }

    // Push adds an item to the stack, blocking if it is full.
    public void push(Object item) throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is full and we are trying to push
            while (top == capacity - 1) {
                wait();
            }

            // Push operation (stack is not full)
            top++;
            stack[top] = item;

            // Notify waiting consumers (if any) that an element is available
            notifyAll();
        }
    }

    // Pop removes and returns the item at the top of the stack, blocking if it is empty.
    public Object pop() throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is empty and we are trying to pop
            while (top == -1) {
                wait();
            }

            // Pop operation (stack is not empty)
            Object item = stack[top];
            top--;

            // Notify waiting producers (if any) that space is available
            notifyAll();
            return item;
        }
    }

    // Peek returns the item at the top without removing it, blocking if the stack is empty.
    public Object peek() throws InterruptedException {
        synchronized (this) {
            // Wait while the stack is empty and we are trying to peek
            while (top == -1) {
                wait();
            }

            // Peek operation
            return stack[top];
        }
    }
}
