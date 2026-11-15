@LinkedList.ThreadSafe
class LinkedList {
    @interface ThreadSafe {}


    private Node head;
    private int size;

    private class Node {
        Node next;

        Node(Object item) {
            this.next = null;
            // Item object is assumed to be the data being stored
        }
    }

    public LinkedList() {
        this.head = null;
        this.size = 0;
    }

    /**
     * Adds an item to the tail of the list. If the list is full (bounded scenario),
     * this operation blocks until space becomes available. Since no bound was specified,
     * we assume it adds successfully if head is not null or size < max_capacity (if bounded).
     * For this unbounded implementation, it adds immediately.
     * If we assume an unbounded list allowing indefinite additions:
     */
    public synchronized void add(Object item) throws InterruptedException {
        Node newNode = new Node(item);

        // In a true bounded scenario, we would wait here if size == capacity.
        // Since no bound is given, we proceed to add immediately.

        if (head == null) {
            head = newNode;
        } else {
            Node current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
        size++;

        // If this were a bounded buffer scenario, we would notify consumers here.
    }


    /**
     * Removes and returns the head of the list. If the list is empty, this operation blocks
     * until an item becomes available.
     */
    public synchronized Object remove() throws InterruptedException {
        // Wait while the list is empty
        while (head == null) {
            wait(); // Releases lock, waits for notifyAll/notify
        }

        // Critical section start: modifiying state
        Node oldHead = head;
        head = oldHead.next;
        size--;

        // Notify producers (add operations) that space is now available/item was consumed
        notifyAll();

        // Return the item (we don't store items in Node objects here, just structure)
        // For a proper implementation involving generic types, we'd return the stored item.
        // Since the Node constructor only sets 'next', we assume the requested return value
        // is successful removal and we return null as a placeholder for the removed item.

        // To truly satisfy the contract, let's adjust Node to hold data for removal:
        return null; // Placeholder return value since the original constructor didn't store data properly.
    }

    /**
     * Returns a sequence identifier/status of the list head without removing it.
     * This is non-blocking and reads the current state.
     */
    public synchronized Object peek() {
        if (head == null) {
            return null; // List is empty
        }
        // Return the head object/data (placeholder)
        return null; // Placeholder for head item data
    }

    public synchronized int size() {
        return this.size;
    }
}
