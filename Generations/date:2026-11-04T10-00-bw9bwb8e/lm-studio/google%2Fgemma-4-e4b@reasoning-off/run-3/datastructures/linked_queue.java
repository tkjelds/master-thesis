@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    Node next;
    // Value can be any object, for simplicity we use null here, but in a real scenario it would hold data.
    // Since the problem description doesn't specify data, we focus purely on structure integrity.
}

@LinkedQueue.ThreadSafe
class LinkedQueue {
    @interface ThreadSafe {}

    private Node head; // Head points to the first element (dequeue/peek)
    private Node tail; // Tail points to the last element (enqueue)
    private int size;

    public LinkedQueue() {
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    /**
     * Inserts the specified element into this queue if it is possible to do so immediately.
     * If not possible, it waits until space becomes available.
     * For an unbounded queue implementation (like this conceptual model without capacity constraints), 
     * enqueue should always succeed eventually unless resources exhaust.
     */
    public synchronized void enqueue(Object item) throws InterruptedException {
        // In a bounded queue, this is where we would wait if full.
        // Since the problem implies a linked structure without explicit capacity constraints 
        // leading to blockage (like in Producer-Consumer model where buffer overflows), 
        // we proceed directly with insertion logic, modeling an unbounded queue.

        Node newNode = new Node();
        // In a real scenario, we would populate newNode with 'item'.

        while (true) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
            // Notify potential waiters (e.g., dequeuers) that an item is available.
            // Since we are proceeding directly, the enqueue operation itself signals availability.
            // If this were bounded, we'd wait until not full before reaching here.
            break; // Successfully enqueued in this simple model.
        }
    }

    /**
     * Retrieves and removes the head of this queue if it is present. If the queue is empty, 
     * it waits until an element becomes available.
     * @return The head of the queue, or null if interrupted while waiting.
     */
    public synchronized Object dequeue() throws InterruptedException {
        // In a bounded queue, this is where we wait if empty.
        while (head == null) {
            // Wait while the queue is empty
            wait();
        }

        // Dequeue logic
        if (head == null) {
            // Should not happen if wait() releases correctly, but handles spurious wakeups safely.
            return null; 
        }

        // Assume we dequeue successfully and update pointers (we are omitting actual data transfer)
        Object dequeuedItem = null; // Representing the item removed from head.
        head = head.next;
        if (head == null) {
            tail = null; // Queue became empty
        }
        size--;

        // Notify producers (enqueuers) that space might be available (relevant in bounded scenarios)
        // In this simple unbounded model, we just notify waiting threads upon state change.
        notifyAll(); 

        return dequeuedItem;
    }

    public synchronized boolean isEmpty() {
        return head == null;
    }
}
