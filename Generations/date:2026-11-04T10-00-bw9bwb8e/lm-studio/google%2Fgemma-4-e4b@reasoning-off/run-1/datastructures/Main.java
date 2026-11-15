@MyLinkedList.ThreadSafe
class MyLinkedList {
    @interface ThreadSafe {}


    static class Node {
        Node next;
        // Value is not strictly needed for concurrency control demonstration, 
        // but good practice to include it.
        Object value;

        Node(Object val) {
            this.value = val;
            this.next = null;
        }
    }

    private Node head;
    private int size;

    public MyLinkedList() {
        this.head = null;
        this.size = 0;
    }

    /**
     * Inserts a new element at the tail of the list.
     * Blocks if the operation cannot complete immediately (not applicable here for addTail, 
     * but blocks on general condition if we modeled a bounded buffer).
     * Since this is an unbounded list, it should complete quickly unless contention occurs 
     * on the internal state modification.
     */
    public synchronized void addTail(Object value) {
        Node newNode = new Node(value);

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
     * Retrieves and removes the first element of the list.
     * Blocks if the list is empty until an element becomes available.
     */
    public synchronized Object removeHead() throws InterruptedException {
        while (head == null) {
            // Wait until head is not null
            wait(); 
        }

        Node oldHead = head;
        head = oldHead.next;
        size--;

        // Notify potential waiters that the list is no longer empty/has changed
        notifyAll(); 

        return oldHead.value;
    }

    /**
     * Returns the current size of the list. Blocks if the list is empty and a signal 
     * that the size might have changed is expected/necessary, though for simple read access, 
     * it just returns the current count. To strictly adhere to waiting for a non-empty state 
     * if size checking implies transactional consistency with removal, we might wait.
     * For a simple query, we just return the size under lock.
     */
    public synchronized int size() {
        // While this operation itself doesn't logically need to block waiting for data, 
        // all modifications happen under the same lock, ensuring visibility.
        return size;
    }

    /**
     * Decrements the size and removes the head element, blocking until an element is present.
     * @return The removed object.
     */
    public synchronized Object takeHead() throws InterruptedException {
        while (head == null) {
            wait(); // Wait if the list is empty
        }

        Node oldHead = head;
        head = oldHead.next;
        size--;

        notifyAll(); // Notify producers/waiters that space/presence has changed
        return oldHead.value;
    }

    /**
     * Adds an element to the tail, notifying any threads waiting for availability.
     */
    public synchronized void offerTail(Object value) throws InterruptedException {
        // Wait if the structure was bounded and full (not modeled here, but place holder)

        Node newNode = new Node(value);

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

        // Notify threads waiting on takeHead/removeHead that data is now available
        notifyAll(); 
    }
}

// Example usage structure (not part of the required output, but shows context)
/*
public class Main {
    public static void main(String[] args) throws InterruptedException {
        MyLinkedList list = new MyLinkedList();

        // Consumer Thread (Taker)
        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    Object item = list.takeHead();
                    System.out.println("Consumed: " + item);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // Producer Thread (Adder)
        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 5; i++) {
                    Thread.sleep(100); // Simulate work before producing
                    list.offerTail("Item " + i);
                }
            } catch (InterruptedException e) {}
        });

        consumer.start();
        producer.start();
    }
}
*/
