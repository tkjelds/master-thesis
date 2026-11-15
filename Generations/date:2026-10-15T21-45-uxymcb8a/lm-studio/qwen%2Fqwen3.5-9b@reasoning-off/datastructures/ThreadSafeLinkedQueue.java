@ThreadSafeLinkedQueue.ThreadSafe
public class ThreadSafeLinkedQueue {
    @interface ThreadSafe {}

    // Node class representing an element in the linked list
    private static class Node {
        final Object data;
        Node next;

        Node(Object data) {
            this.data = data;
        }
    }

    // Sentinel node to mark the head of the queue
    private final Node sentinel;
    
    // Pointers to the head and tail of the actual data
    private Node head;
    private Node tail;
    
    // Count of elements to determine if queue is empty/full (if bounded)
    // We assume a capacity of Long.MAX_VALUE for simplicity as no limit was specified,
    // but we implement counters anyway to track size if we decide to add bounds later.
    private long count;

    // Maximum capacity. Set to a high value to simulate unbounded or very large buffer.
    private static final long MAX_CAPACITY = Long.MAX_VALUE - 1;

    public ThreadSafeLinkedQueue() {
        // Create a sentinel node that acts as the head of the list.
        // This simplifies adding to the front (we don't need to check if empty 
        // to get the tail for insertion).
        sentinel = new Node(null);
        // Initialize head and tail to the sentinel
        head = sentinel;
        tail = sentinel;
        count = 0;
    }

    /**
     * Adds the element onto the head of this queue.
     * Waits if the queue is full.
     * Returns true if the element was added, false if this method could not
     * cause the field of the queue to change (if the queue was full and timed out,
     * though we use blocking wait, so it never returns false unless explicitly stopped).
     * 
     * Note: Since we don't implement a time-limited offer, if the queue is full,
     * this call will block indefinitely.
     */
    public void add(Object e) {
        while (count >= MAX_CAPACITY) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e2) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during add", e2);
                }
                // Re-check conditions after waking up
                if (count >= MAX_CAPACITY) {
                    throw new RuntimeException("Queue overflow");
                }
            }
        }

        Node newNode = new Node(e);
        synchronized (this) {
            head = newNode;
            head.next = sentinel;
            count++;
            
            // Wake up any threads waiting to remove
            notifyAll();
        }
    }

    /**
     * Waits until an element is added to this queue.
     * Returns the head node (to be removed later).
     */
    public synchronized Node awaitHead() {
        while (head == sentinel) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during await", e);
            }
        }
        return head;
    }

    /**
     * Removes and returns the element at the head of this queue.
     * Waits if the queue is empty.
     * 
     * Note: Using a separate method for removal logic to keep 'add' clean,
     * though in a real single-file interface, we might expose a single remove method.
     * Here we assume the standard design where head is accessed via synchronization.
     */
    public Object remove() {
        // We assume this is called when we know the queue is not empty,
        // or we must block if it is. Let's implement blocking removal.
        while (head == sentinel) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during remove", e);
                }
            }
            // After waking, check again (visibility + state change)
            synchronized (this) {
                if (head == sentinel) {
                    throw new RuntimeException("Queue still empty after wait");
                }
            }
        }

        synchronized (this) {
            Node ret = head;
            head = head.next;
            
            if (head == sentinel) {
                tail = head;
            }
            
            count--;
            ret.data = null; // Allow GC
            return ret.data;
        }
    }

    /**
     * Checks if this queue is empty.
     */
    public synchronized boolean isEmpty() {
        return head == sentinel;
    }

    /**
     * Checks if this queue is full.
     */
    public synchronized boolean isFull() {
        return count >= MAX_CAPACITY;
    }

    /**
     * Returns the number of elements in this queue.
     */
    public synchronized long size() {
        return count;
    }

    /**
     * Wait until a thread can add an element.
     * This is a convenience method that adds logic to wait if full, 
     * then adds.
     */
    public Object put(Object e) {
        add(e);
        return e;
    }

    /**
     * Wait until a thread can remove an element.
     * This is a convenience method that waits if empty,
     * then removes.
     */
    public Object take() {
        return remove();
    }
}
