@ConcurrentLinkedDeque.ThreadSafe
class ConcurrentLinkedDeque {
    @interface ThreadSafe {}


    private static class Node {
        Node next;
        Node prev;
        Object value;

        Node(Object value) {
            this.value = value;
        }
    }

    private Node head; // Dummy head node
    private Node tail; // Dummy tail node
    private int size;

    public ConcurrentLinkedDeque() {
        // Initialize with dummy head and tail nodes
        head = new Node(null);
        tail = new Node(null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    /**
     * Adds an element to the tail of the deque. Blocks if the deque is bounded and full.
     * Since this problem description implies an unbounded deque by not specifying a capacity,
     * we will implement addLast as adding to the tail of an unbounded deque, which never blocks on fullness.
     * If capacity constraints were present, wait/notifyAll would be used here.
     * For this unbounded implementation, it always succeeds immediately after synchronization overhead.
     * @param item The element to add.
     */
    public void addLast(Object item) throws InterruptedException {
        Node newNode = new Node(item);

        synchronized (this) {
            // Link new node between current tail.prev and tail
            Node last = tail.prev;

            newNode.next = tail;
            newNode.prev = last;
            tail.prev = newNode;
            last.next = newNode;
            size++;
        }
    }

    /**
     * Retrieves and removes the first element of the deque. Blocks if the deque is empty.
     * @return The removed object.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object removeFirst() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                // Wait until an element is available
                wait();
            }

            // Head.next is the first actual element (since head is dummy)
            Node first = head.next;
            Object value = first.value;

            // Remove node from structure
            head.next = first.next;
            first.next.prev = head;
            size--;

            // Signal that the deque is no longer empty
            notifyAll();
            return value;
        }
    }

    /**
     * Retrieves, but does not remove, the first element of the deque. Returns null if empty.
     * This implementation matches the behavior of peek() in a typical deque, not blocking/waiting.
     * If blocking read was required, the logic would involve wait/notifyAll similar to removeFirst.
     * @return The first element, or null if the deque is empty.
     */
    public Object peekFirst() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            // Head.next is the first actual element
            return head.next.value;
        }
    }

    /**
     * Removes the last element of the deque. Blocks if the deque is empty.
     * @return The removed object.
     * @throws InterruptedException if interrupted while waiting.
     */
    public Object removeLast() throws InterruptedException {
        synchronized (this) {
            while (size == 0) {
                // Wait until an element is available
                wait();
            }

            // Tail.prev is the last actual element (since tail is dummy)
            Node last = tail.prev;
            Object value = last.value;

            // Remove node from structure
            Node first = head.next; // current first element
            last.prev = null; // Isolate the removed node (optional cleanup)

            head.next = tail;
            tail.prev = head;
            size--;

            // Signal that the deque is no longer empty
            notifyAll();
            return value;
        }
    }

    /**
     * Retrieves, but does not remove, the last element of the deque. Returns null if empty.
     * @return The last element, or null if the deque is empty.
     */
    public Object peekLast() {
        synchronized (this) {
            if (size == 0) {
                return null;
            }
            // Tail.prev is the last actual element
            return tail.prev.value;
        }
    }

    /**
     * Returns the current size of the deque.
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
}
