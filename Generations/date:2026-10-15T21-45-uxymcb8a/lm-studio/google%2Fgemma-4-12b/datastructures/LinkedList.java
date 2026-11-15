@LinkedList.ThreadSafe
public class LinkedList<T> {
    @interface ThreadSafe {}

    private Node head;
    private Node tail;
    private int size;
    private final int capacity;
    private final Object lock = new Object();

    public LinkedList(int capacity) {
        this.capacity = capacity;
    }

    private class Node {
        T data;
        Node next;
        Node prev;

        Node(T data) {
            this.data = data;
        }
    }

    /**
     * Adds an element to the end of the list.
     * Blocks if the list is at capacity until a space becomes available.
     */
    public void add(T data) throws InterruptedException {
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }

            Node newNode = new Node(data);
            if (head == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                newNode.prev = tail;
                tail = newNode;
            }
            size++;

            // Notify any threads waiting for a spot to be available (though it's full)
            // or if the list was previously empty/filling.
            lock.notifyAll();
        }
    }

    /**
     * Removes the first occurrence of the specified element from the list.
     * Returns true if the element was removed, false otherwise.
     */
    public boolean remove(T data) {
        synchronized (lock) {
            Node current = head;
            while (current != null) {
                boolean isMatch = false;
                if (data == null) {
                    if (current.data == null) {
                        isMatch = true;
                    }
                } else {
                    if (data.equals(current.data)) {
                        isMatch = true;
                    }
                }

                if (isMatch) {
                    if (current.prev != null) {
                        current.prev.next = current.next;
                    } else {
                        head = current.next;
                    }

                    if (current.next != null) {
                        current.next.prev = current.prev;
                    } else {
                        tail = current.prev;
                    }

                    size--;
                    // Notify potential waiting threads that a space is now available.
                    lock.notifyAll();
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    /**
     * Returns the current size of the list.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Retrieves the element at the specified index.
     */
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }

            Node current = head;
            for (int i = 0; i < index; i++) {
                current = current.next;
            }
            return current.data;
        }
    }

    /**
     * Checks if the list is empty.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the capacity of the list.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Clears the list.
     */
    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
        }
    }
}
