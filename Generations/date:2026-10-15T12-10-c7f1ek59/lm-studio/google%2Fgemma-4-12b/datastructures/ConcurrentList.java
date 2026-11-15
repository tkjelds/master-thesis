@ConcurrentList.ThreadSafe
public class ConcurrentList {
    @interface ThreadSafe {}


    private Node head;
    private Node tail;
    private int size;
    private final int capacity;

    /**
     * Constructs a thread-safe concurrent list with a fixed capacity.
     * 
     * @param capacity The maximum number of elements the list can hold.
     */
    public ConcurrentList(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Adds an item to the end of the list.
     * If the list is at capacity, this method blocks until space becomes available.
     * 
     * @param item The object to add.
     * @throws InterruptedException if the thread is interrupted while waiting for space.
     */
    public void add(Object item) throws InterruptedException {
        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }

            Node newNode = new Node(item);
            if (head == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }

            size++;
            this.notifyAll();
        }
    }

    /**
     * Retrieves the item at the specified index.
     * 
     * @param index The index of the element to retrieve.
     * @return The object at the given index, or null if the index is out of bounds.
     */
    public Object get(int index) {
        synchronized (this) {
            if (index < 0 || index >= size) {
                return null;
            }

            Node current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current.data;
        }
    }

    /**
     * Removes the item at the specified index.
     * 
     * @param index The index of the element to remove.
     */
    public void remove(int index) {
        synchronized (this) {
            if (index < 0 || index >= size) {
                return;
            }

            Node current;
            if (index == 0) {
                current = head;
                head = head.next;
                if (head == null) {
                    tail = null;
                }
            } else {
                Node prev = head;
                for (int i = 0; i < index - 1; i++) {
                    prev = prev.next;
                }
                current = prev.next;
                prev.next = current.next;
                if (prev.next == null) {
                    tail = prev;
                }
            }

            size--;
            this.notifyAll();
        }
    }

    /**
     * Returns the current number of elements in the list.
     * 
     * @return The size of the list.
     */
    public int size() {
        synchronized (this) {
            return size;
        }
    }

    /**
     * Internal node structure for the linked list.
     */
    private class Node {
        Object data;
        Node next;

        Node(Object data) {
            this.data = data;
        }
    }
}
