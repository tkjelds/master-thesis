@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    Node next;
}

@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList {
    @interface ThreadSafe {}

    private Node head;
    private int size;

    public ConcurrentLinkedList() {
        this.head = null;
        this.size = 0;
    }

    /**
     * Adds an element to the tail of the list. Blocks if the list is full (not applicable for unbounded, but included for completeness/pattern).
     * Since this is an unbounded linked list implementation scenario without a capacity constraint specified, it will always eventually succeed unless memory runs out.
     * @param element The element to add (represented conceptually, as we are building the structure).
     */
    public synchronized void add(Object element) {
        // In a real scenario, 'element' would be used. Here we just focus on structural add for simplicity.
        Node newNode = new Node();
        newNode.next = null;

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
    }

    /**
     * Removes and returns the element at the head of the list. Blocks if the list is empty.
     * @return The element removed (conceptually, as we are only managing structure).
     * @throws InterruptedException if the waiting thread is interrupted.
     */
    public synchronized Object remove() throws InterruptedException {
        // Block until the list is not empty.
        while (head == null) {
            wait(); // Releases monitor lock and waits until notified.
        }

        // Once we pass the loop, head is not null. Proceed with removal.
        Node first = head;
        head = head.next;
        size--;

        // Notify waiting threads that the list is no longer empty (if removal occurred) or possibly not full (if bounded).
        // Since this is an unbounded add scenario, notifying about removal readiness is good practice.
        notifyAll(); // Notify potential waiting 'add' operations if we enforced capacity bounds later.

        // Return conceptual element/node presence marker
        return null; // Returning null to signify successful removal in this structural implementation exercise.
    }

    /**
     * Returns the current size of the list. Blocks if structure modification is ongoing during measurement (though synchronized block prevents external races).
     * @return The current size.
     */
    public synchronized int getSize() {
        // Simple read of the size protected by synchronization.
        return size;
    }

    /**
     * Checks if the list is empty.
     * @return true if the list is empty, false otherwise.
     */
    public synchronized boolean isEmpty() {
        return head == null;
    }

    // --- Main method for testing/demonstration purposes (optional but useful to make it runnable) ---

    public static void main(String[] args) throws InterruptedException {
        ConcurrentLinkedList list = new ConcurrentLinkedList();

        // Test sequence: Add elements
        Runnable adder = () -> {
            for (int i = 0; i < 5; i++) {
                System.out.println(Thread.currentThread().getName() + ": Adding element.");
                list.add(null); // Add conceptual element
            }
        };

        // Test sequence: Remove elements concurrently
        Runnable remover = () -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Object removed = list.remove();
                    System.out.println(Thread.currentThread().getName() + ": Removed element.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Run tests in separate threads
        Thread t1 = new Thread(adder, "Adder-1");
        Thread t2 = new Thread(remover, "Remover-1");

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        System.out.println("Testing finished. Final Size: " + list.getSize());
    }
}
