@DelayQueue.ThreadSafe
public class DelayQueue<T> {
    @interface ThreadSafe {}


    /**
     * Internal node representation for the linked list.
     */
    private static class Node<T> {
        T value;
        long expiryTime;
        Node<T> next;

        Node(T value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiry;
        }
    }

    private Node<T> headDummy; // Dummy node to simplify removal of the first element
    private Node<T> tail;      // Points to the last node in the list
    private int size = 0;
    private final int capacity;

    /**
     * Initializes a new DelayQueue with the specified capacity.
     * @param capacity The maximum number of elements allowed in the queue.
     */
    public DelayQueue(int capacity) {
        this.capacity = capacity;
        this.headDummy = new Node<>(null, 0);
    }

    /**
     * Inserts a new item into the delay queue. 
     * If the queue is full, this method blocks until space becomes available.
     * 
     * @param item The element to add.
     * @param delayMs The time in milliseconds until the element becomes available.
     */
    public void put(T item, long delayMs) {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }

        long expiry = System.currentTimeMillis() + delayMs;

        synchronized (this) {
            // Wait until there is space in the queue.
            while (size == capacity) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Standard practice: maintain consistent state or re-interrupt.
                }
            }

            Node<T> newNode = new Node<>(item, expiry);
            if (headDummy.next == null) {
                // Case: Queue is empty.
                headDummy.next = newNode;
                tail = newNode;
            } else if (expiry < headDummy.next.expiry) {
                // Case: New node belongs at the front of the queue.
                newNode.next = headDummy.next;
                headDummy.next = newNode;
            } else {
                // Case: Insert node in sorted order based on expiry time.
                Node<T> current = headDummy;
                while (current.next != null && current.next.expiry <= expiry) {
                    current = current.next;
                }
                newNode.next = current.next;
                current.next = newNode;
                if (current == tail) {
                    tail = newNode;
                }
            }

            size++;
            // Notify any threads waiting to put or take.
            notifyAll();
        }
    }

    /**
     * Retrieves and removes the head of this queue, or waits if no items are ready.
     * 
     * @return The oldest/ready item.
     */
    public T take() {
        synchronized (this) {
            while (true) {
                // Block if queue is empty.
                if (size == 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Task interrupted.
                    }
                } else if (headDummy.next.expiry <= System.currentTimeMillis()) {
                    // Item is ready for removal.
                    Node<T> nodeToTake = headDummy.next;
                    T result = nodeToTake.value;

                    // Remove the node from the list.
                    headDummy.next = nodeToTake.next;
                    if (headDummy.next == null) {
                        tail = null;
                    }

                    size--;
                    // Notify any threads waiting for space in the queue.
                    notifyAll();
                    return result;

                } else {
                    // Item is not yet ready, wait until notified.
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        // Task interrupted.
                    }
                }
            }
        }
    }

    /**
     * Returns the current size of the queue.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }
}

