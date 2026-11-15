/**
 * A thread-safe set of int values, stored in ascending order.
 *
 * Storage grows as needed, subject to available memory. Operations do not
 * wait for elements or capacity; they block only while acquiring the monitor.
 * Every operation is atomic with respect to the other operations.
 *
 * Construction must complete before the instance is shared with other threads.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet {
    @interface ThreadSafe {}

    private static final class Monitor {
    }

    private static final class Node {
        final int value;
        Node next;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Monitor monitor = new Monitor();
    private Node head;
    private long count;

    public ConcurrentSet() {
    }

    /**
     * Adds value, returning true if it was not already present.
     */
    public boolean add(int value) {
        synchronized (monitor) {
            Node previous = null;
            Node current = head;

            while (current != null && current.value < value) {
                previous = current;
                current = current.next;
            }

            if (current != null && current.value == value) {
                return false;
            }

            Node added = new Node(value, current);
            if (previous == null) {
                head = added;
            } else {
                previous.next = added;
            }

            count++;
            return true;
        }
    }

    /**
     * Removes value, returning true if it was present.
     */
    public boolean remove(int value) {
        synchronized (monitor) {
            Node previous = null;
            Node current = head;

            while (current != null && current.value < value) {
                previous = current;
                current = current.next;
            }

            if (current == null || current.value != value) {
                return false;
            }

            if (previous == null) {
                head = current.next;
            } else {
                previous.next = current.next;
            }

            count--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (monitor) {
            Node current = head;

            while (current != null && current.value < value) {
                current = current.next;
            }

            return current != null && current.value == value;
        }
    }

    /**
     * Returns the number of elements. A long accommodates the entire int domain.
     */
    public long size() {
        synchronized (monitor) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (monitor) {
            head = null;
            count = 0;
        }
    }
}
