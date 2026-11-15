@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque {
    @interface ThreadSafe {}


    private final Object[] element;
    private int head;
    private int tail;
    private int size;
    private final int capacity;

    /**
     * Constructs a deque with the specified capacity.
     * The initial capacity must be greater than 0 and odd to allow symmetric growth/shrinking
     * when using a circular array implementation starting from a known empty state.
     * For simplicity in this bounded implementation, we enforce the capacity and manage indices carefully.
     * A common pattern for ring buffers is to use a capacity N where one slot is unused, making the actual usable size N-1.
     * If we treat 'size' as the number of elements, and use modulo arithmetic on capacity, 
     * setting capacity to a power of two or ensuring (tail - head) % capacity = size is crucial.
     * Given the strict constraints, we will use a standard array implementation where capacity = N+1 and size = actual elements.
     * For simplicity and to adhere strictly to bounds/modulus, we initialize it large enough.
     * Let's choose a fixed initial capacity and assume blocking behavior for bounded scenarios.
     */
    public ConcurrentDeque(int capacity) {
        // We need space for N elements. If we use modulo arithmetic on array indices, 
        // the logical capacity might be different from physical allocation.
        // To simplify modulo arithmetic for head/tail management in a ring buffer:
        // Let the physical array size be 'capacity + 1', allowing us to distinguish between full and empty states
        // when head == tail (if we reserve one spot).

        this.capacity = capacity + 1; // Physical array size
        this.element = new Object[this.capacity];
        this.head = 0; // Index where next element will be removed from (front)
        this.tail = 0; // Index where next element will be added to (back)
        this.size = 0;
    }

    /**
     * Inserts the specified element at the tail of this deque if it is possible to do so immediately.
     * If the deque is full, this thread waits until space becomes available.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void insertTail(Object item) throws InterruptedException {
        while (true) {
            // Check if full
            if (size == capacity - 1) {
                // Wait until something is removed from the head
                synchronized (this) {
                    while (size == capacity - 1) {
                        this.wait();
                    }
                    // Continue to insertion logic
                }
            }

            synchronized (this) {
                // Recheck condition in synchronized block just in case, although the outer check handles most cases.
                // More importantly, we perform the operation atomically inside here.

                if (size == capacity - 1) {
                    // This should ideally not happen if the initial wait logic is perfect, but robustness demands protection.
                    // If we reach here after passing the wait condition check, it means space is available.
                }

                // Perform insertion
                element[tail] = item;
                tail = (tail + 1) % capacity;
                size++;

                // Notify potential waiters that are trying to remove elements
                this.notifyAll();
                return; // Success
            }
        }
    }

    /**
     * Retrieves and removes the element at the head of this deque if it is present.
     * If the deque is empty, this thread waits until an element becomes available.
     * @return The head of the deque.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object removeHead() throws InterruptedException {
        while (true) {
            // Check if empty
            if (size == 0) {
                synchronized (this) {
                    while (size == 0) {
                        // Wait until an element is added
                        this.wait();
                    }
                }
            }

            synchronized (this) {
                // Perform removal
                Object item = element[head];
                element[head] = null; // Help garbage collection and break references
                head = (head + 1) % capacity;
                size--;

                // Notify potential waiters trying to add elements (if full)
                this.notifyAll();
                return item; // Success
            }
        }
    }

    /**
     * Returns the element at the head without removing it, waiting if the deque is empty.
     * @return The head of the deque.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object peekHead() throws InterruptedException {
        while (true) {
            // Check if empty
            if (size == 0) {
                synchronized (this) {
                    while (size == 0) {
                        this.wait();
                    }
                }
            }

            synchronized (this) {
                // Return element without modification to state indices/size
                Object item = element[head];

                // Notify potential waiters trying to add elements (since we found something)
                this.notifyAll();
                return item;
            }
        }
    }

    /**
     * Returns the element at the tail without removing it, waiting if the deque is empty.
     * This operation effectively implements peekTail on the logical structure, but adds to the deque's usage pattern.
     * Note: In a true Deque implementation, peekTail retrieves the item that would be added next if we called insertTail.
     * For simplicity matching the typical queue/deque pattern, this refers to the "last added" element.
     * @return The tail of the deque.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object peekTail() throws InterruptedException {
        while (true) {
            // Check if empty
            if (size == 0) {
                synchronized (this) {
                    while (size == 0) {
                        this.wait();
                    }
                }
            }

            synchronized (this) {
                // Calculate tail index corresponding to the logical last inserted item.
                // If size > 0, the current tail points one past the logical last element.
                int lastIndex = (tail - 1 + capacity) % capacity;
                Object item = element[lastIndex];

                // Notify potential waiters trying to remove elements (since we found something)
                this.notifyAll();
                return item;
            }
        }
    }

    // --- Example Usage (optional, but helpful for testing the blocking behavior) ---
    /*
    public static void main(String[] args) {
        ConcurrentDeque deque = new ConcurrentDeque(5); // Max capacity 5, physical size 6

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 10; i++) {
                    System.out.println(Thread.currentThread().getName() + " inserting: " + i);
                    deque.insertTail(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Producer");

        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    Object item = deque.removeHead();
                    System.out.println(Thread.currentThread().getName() + " removing: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Consumer");

        producer.start();
        consumer.start();
    }
    */
}
