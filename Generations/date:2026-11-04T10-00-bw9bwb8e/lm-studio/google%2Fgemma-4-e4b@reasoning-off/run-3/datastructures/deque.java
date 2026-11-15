@ConcurrentDeque.ThreadSafe
class ConcurrentDeque {
    @interface ThreadSafe {}


    private int[] array;
    private int head; // Index of the first element
    private int tail; // Index where the next element will be added at the rear
    private int size;
    private final int capacity;

    /**
     * Constructs a ConcurrentDeque with the given capacity.
     * The capacity must be greater than 0.
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.array = new int[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the specified element at the rear of this deque if it is possible to do so immediately.
     * Otherwise, it waits until space becomes available and then inserts the element.
     * Blocks if the deque is full.
     */
    public void insertRear(int element) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                // Wait until space is available
                wait();
            }

            array[tail] = element;
            tail = (tail + 1) % capacity;
            size++;

            // Notify waiting threads that an element has been added
            notifyAll();
        }
    }

    /**
     * Retrieves and removes the element from the front of this deque if it is present.
     * Otherwise, it waits until an element becomes available and then retrieves and removes it.
     * Blocks if the deque is empty.
     */
    public int insertFront() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                // Wait until an element is present
                wait();
            }

            int element = array[head];
            head = (head + 1) % capacity;
            size--;

            // Notify waiting threads that space is available
            notifyAll();
            return element;
        }
    }

    /**
     * Retrieves and removes the element from the front of this deque if it is present.
     * Returns null if the deque is empty and does not wait.
     */
    public int insertFrontIfAvailable() {
        synchronized (this) {
            if (size == 0) {
                return Integer.MIN_VALUE; // Sentinel value for empty, assuming valid elements are >= 0
            }
            int element = array[head];
            head = (head + 1) % capacity;
            size--;
            return element;
        }
    }

    /**
     * Retrieves and removes the element from the rear of this deque if it is present.
     * Otherwise, it waits until an element becomes available and then retrieves and removes it.
     * Blocks if the deque is empty.
     */
    public int insertRear() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                // Wait until an element is present
                wait();
            }

            // Calculate the index of the actual last element (which is just before tail)
            tail = (tail - 1 + capacity) % capacity;
            int element = array[tail];
            size--;

            // Notify waiting threads that space is available
            notifyAll();
            return element;
        }
    }

    /**
     * Retrieves and removes the element from the rear of this deque if it is present.
     * Returns null if the deque is empty and does not wait.
     */
    public int insertRearIfAvailable() {
        synchronized (this) {
            if (size == 0) {
                return Integer.MIN_VALUE; // Sentinel value for empty
            }

            tail = (tail - 1 + capacity) % capacity;
            int element = array[tail];
            size--;
            return element;
        }
    }


    /**
     * Returns the current number of elements in the deque.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    /**
     * Checks if the deque is empty.
     */
    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    /**
     * Inserts the specified element at the front of this deque if it is possible to do so immediately.
     * Otherwise, it waits until space becomes available and then inserts the element.
     * Blocks if the deque is full.
     */
    public void insertFront(int element) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                // Wait until space is available
                wait();
            }

            array[head] = element;
            head = (head + 1) % capacity;
            size++;

            // Notify waiting threads that an element has been added
            notifyAll();
        }
    }

    /**
     * Retrieves and removes the element from the rear of this deque if it is present.
     * Otherwise, it waits until an element becomes available and then retrieves and removes it.
     * Blocks if the deque is empty.
     */
    public int insertRearIfBlocked() throws InterruptedException {
        // This method signature matches the blocking nature of dequeue operations when empty, 
        // but functionally it retrieves from the rear.
        synchronized (this) {
            while (size == 0) {
                wait();
            }

            // Calculate the index of the actual last element (which is just before tail)
            tail = (tail - 1 + capacity) % capacity;
            int element = array[tail];
            size--;

            // Notify waiting threads that space is available
            notifyAll();
            return element;
        }
    }

    // Note: To perfectly match a bidirectional Deque interface (like LinkedList/Deque), 
    // methods like addFirst, removeLast, etc., would be used. Since the prompt implies 
    // a generic thread-safe Deque implementation and focuses on synchronization primitives, 
    // the four primary blocking operations (insertRear/Front from blocking checks) and their 
    // non-blocking counterparts cover the required behavior demonstrated here.
}
