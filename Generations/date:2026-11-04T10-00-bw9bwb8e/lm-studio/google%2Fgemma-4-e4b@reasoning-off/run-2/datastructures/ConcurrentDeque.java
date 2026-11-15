@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque {
    @interface ThreadSafe {}


    private Object[] element;
    private int head;
    private int tail;
    private int size;
    private final int capacity;

    /**
     * Constructs a bounded deque with the specified capacity.
     * The deque is initialized to be empty.
     */
    public ConcurrentDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        // We use an array of Objects to allow storing various types and manage nullability if needed,
        // although for this simulation, we mostly rely on size/pointers.
        this.element = new Object[capacity];
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    /**
     * Inserts the specified element at the tail of this deque if it is possible to do so immediately.
     * If the deque is full, this method blocks until space becomes available, and then inserts the element.
     * @param item the element to add
     * @throws InterruptedException if interrupted while waiting
     */
    public void insertLast(Object item) throws InterruptedException {
        while (true) {
            synchronized (this) {
                // Wait while the deque is full
                while (size == capacity) {
                    wait();
                }

                // Insert the element at the tail position
                element[tail] = item;
                tail = (tail + 1) % capacity;
                size++;

                // Notify waiting threads that the deque is no longer empty (or has space)
                notifyAll();
                return; // Operation successful
            }
        }
    }

    /**
     * Retrieves and removes the element from the head of this deque if it is present.
     * If the deque is empty, this method blocks until an element becomes available, and then retrieves it.
     * @return the head of this deque
     * @throws InterruptedException if interrupted while waiting
     */
    public Object removeFirst() throws InterruptedException {
        while (true) {
            synchronized (this) {
                // Wait while the deque is empty
                while (size == 0) {
                    wait();
                }

                // Retrieve the element from the head position
                Object item = element[head];
                element[head] = null; // Help garbage collection
                head = (head + 1) % capacity;
                size--;

                // Notify waiting threads that the deque is no longer full (space available)
                notifyAll();
                return item; // Operation successful
            }
        }
    }

    /**
     * Returns the element at the head of this deque without removing it.
     * This method blocks if the deque is empty until an element is available.
     * @return the head of this deque
     * @throws InterruptedException if interrupted while waiting
     */
    public Object peekFirst() throws InterruptedException {
        while (true) {
            synchronized (this) {
                // Wait while the deque is empty
                while (size == 0) {
                    wait();
                }

                // Peek the element at the head position (do not advance pointers or change size)
                Object item = element[head];

                // Notify waiting threads that the deque is not empty anymore (though it already was)
                notifyAll(); 
                return item; // Operation successful
            }
        }
    }

    /**
     * Returns the current number of elements in this deque.
     * This operation is atomic with respect to concurrent modifications via synchronized blocks on 'this'.
     * @return the number of elements in deque
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    /**
     * Checks if this deque is empty.
     * @return true if the deque contains no elements, false otherwise
     */
    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    // --- Main method for simple testing/demonstration (optional but helpful for verification) ---
    public static void main(String[] args) {
        final int CAP = 5;
        ConcurrentDeque deque = new ConcurrentDeque(CAP);

        Runnable producer = () -> {
            try {
                for (int i = 1; i <= 8; i++) {
                    System.out.println(Thread.currentThread().getName() + " attempting to insert: " + i);
                    deque.insertLast(i);
                    System.out.println(Thread.currentThread().getName() + " inserted: " + i);
                    // Introduce a slight delay outside the synchronized block to allow consumer to run
                    Thread.sleep(100); 
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Runnable consumer = () -> {
            try {
                for (int i = 0; i < 8; i++) {
                    Object item = deque.removeFirst();
                    System.out.println(Thread.currentThread().getName() + " removed: " + item);
                    // Introduce a slight delay outside the synchronized block
                    Thread.sleep(250); 
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread producerThread = new Thread(producer, "Producer");
        Thread consumerThread = new Thread(consumer, "Consumer");

        producerThread.start();
        consumerThread.start();

        try {
            producerThread.join();
            consumerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
