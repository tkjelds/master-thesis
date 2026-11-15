@MyLinkedListQueue.ThreadSafe
class MyLinkedListQueue {
    @interface ThreadSafe {}


    private static class Node {
        Node next;
        Object item;

        Node(Object item) {
            this.item = item;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public MyLinkedListQueue() {
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    /**
     * Inserts the specified item into this queue if it is possible to do so immediately.
     * If not possible, it waits until space becomes available, then inserts the item.
     * @param item The item to add.
     * @throws InterruptedException if interrupted while waiting.
     */
    public void insert(Object item) throws InterruptedException {
        Node newNode = new Node(item);

        synchronized (this) {
            // Wait until the queue is not full. Since this implementation has no fixed capacity,
            // it should always be able to insert unless bounded behavior is introduced.
            // Assuming for this requirement that insertion should always succeed eventually,
            // we don't need to wait for space if the queue is unbounded.
            // If a bounded capacity were present, we would wait here: while (size == CAPACITY) wait();

            while (true) {
                if (tail == null) { // Should only happen in a completely empty state initially
                    head = newNode;
                    tail = newNode;
                } else {
                    newNode.next = tail.next;
                    tail.next = newNode;
                    tail = newNode; // This seems wrong for a singly linked list queue structure.
                                    // Tail should point to the *last* element. Let's fix insertion logic.
                    tail = newNode; // If we treat this as a queue where tail tracks the end:
                }
                // Standard Queue Insertion (Enqueue): Add to the tail.
                if (head == null) { // Truly empty case
                    head = newNode;
                    tail = newNode;
                } else {
                    tail.next = newNode; // Link current tail to new node
                    tail = newNode;      // Move tail pointer to the new node
                }
                size++;
                break; // Successfully inserted (unbounded queue implies no need to wait for space)
            }
        }
    }

    /**
     * Retrieves and removes the head of this queue, if it is present. If not, waits until an element becomes available.
     * @return The head of this queue.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object remove() throws InterruptedException {
        synchronized (this) {
            // Wait until the queue is not empty
            while (head == null) {
                wait(); // Wait if the queue is empty
            }

            // Remove from head
            Object item = head.item;
            head = head.next; // Advance head
            if (head == null) { // Queue became empty after removal
                tail = null;
            }
            size--;

            // Notify potential waiting inserts (if bounded) or just return.
            // Since this is unbounded, we notify if structure allowed potential waiting operations.
        } // Synchronized block ends

        // Notify any threads potentially waiting on insert (if bounded capacity existed)
        notifyAll(); // Should ideally notify whoever is waiting for space if the queue were bounded.

        return item;
    }

    // --- Corrected, simpler implementation using standard queueing invariants for clarity ---
    // Since the problem implies a standard blocking queue behavior (insert blocks if full, remove blocks if empty),
    // and we are implementing a conceptual unbounded queue for simplicity unless capacity is specified,
    // the complexity lies in correctly using wait/notify.

    private Node headCorrected;
    private Node tailCorrected;
    private int sizeCorrected;

    // Re-implementing methods using the corrected structure to satisfy blocking semantics
    public void insertCorrected(Object item) throws InterruptedException {
        Node newNode = new Node(item);

        synchronized (this) {
            // In an unbounded queue, insertion always succeeds eventually. We only block if we must enforce a maximum size.
            // Assuming unbounded: no wait needed on insertion side here.

            if (headCorrected == null) {
                // Queue is empty
                headCorrected = newNode;
                tailCorrected = newNode;
            } else {
                // Queue is not empty, append to tail
                tailCorrected.next = newNode;
                tailCorrected = newNode; // Wait, this makes the new node the tail *and* head? No.
                // Correctly append:
                tailCorrected.next = newNode;
                tailCorrected = newNode; // This makes the *new* node the tail, and its 'next' is null.
            }
            sizeCorrected++;
            // If there were consumers waiting, we notify them now that an item is present.
            notifyAll();
        }
    }

    public Object removeCorrected() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty
            while (headCorrected == null) {
                wait(); // Block until insert signals presence
            }

            // Dequeue operation
            Object item = headCorrected.item;
            headCorrected = headCorrected.next; // Advance head
            if (headCorrected == null) {
                tailCorrected = null; // Queue is now empty, tail must also be nullified
            }
            sizeCorrected--;

            // Signal producers (if bounded capacity existed) that space is available.
            notifyAll(); 

            return item;
        } // End of synchronized block
    }


    // Final selection: Use the "Corrected" logic which accurately models a Producer-Consumer scenario
    // even though the problem description lacked explicit boundaries, blocking queues require signalling upon state change.

    public void insert(Object item) throws InterruptedException {
        insertCorrected(item);
    }

    public Object remove() throws InterruptedException {
        return removeCorrected();
    }
}


@Solution.ThreadSafe
public class Solution {
    @interface ThreadSafe {}


    private static class Node {
        Node next;
        Object item;

        Node(Object item) {
            this.item = item;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size; // Tracked primarily for signaling/invariants, though conceptually unnecessary in unbounded queue

    public void insert(Object item) throws InterruptedException {
        Node newNode = new Node(item);

        synchronized (this) {
            // In an unbounded queue, we don't wait for space. We only ensure sequential access during modification.
            // If this were bounded, we would check size == CAPACITY and wait() here.

            if (head == null) {
                // Queue is empty, first element
                head = newNode;
                tail = newNode;
            } else {
                // Append to the current tail
                tail.next = newNode;
                tail = newNode; // Move tail pointer to the new last element
            }
            size++;

            // Since insertion always succeeds in an unbounded queue, no need to signal consumers unless they were waiting on an empty state.
            notifyAll(); 
        }
    }

    public Object remove() throws InterruptedException {
        synchronized (this) {
            // Wait while the queue is empty
            while (head == null) {
                wait(); // Block if there are no items to remove
            }

            // Dequeue operation (Guaranteed head != null due to the while loop)
            Object item = head.item;
            head = head.next; // Advance head to the next element
            if (head == null) { 
                // If head becomes null, the queue is now empty, so tail must also point to null
                tail = null;
            }
            size--;

            // Signal producers (if this were bounded and waiting on space) that an item was removed.
            notifyAll(); 

            return item;
        } // End of synchronized block - monitor is released, operation complete.
    }

    public static void main(String[] args) {
        // This method is just to make the class runnable/testable, but not part of the required queue implementation.
        // A proper test harness would demonstrate concurrent access.
    }
}
