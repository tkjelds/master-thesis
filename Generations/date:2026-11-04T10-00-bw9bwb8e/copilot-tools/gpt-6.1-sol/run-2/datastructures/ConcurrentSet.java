/**
 * A thread-safe, unbounded set of integer values.
 *
 * Operations are serialized using a private intrinsic monitor.
 * Adding a duplicate or removing an absent value returns false.
 * Empty-set queries return immediately; no operation waits for contents.
 * Values in snapshots are sorted in ascending order.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet {
    @interface ThreadSafe {}

    private static final class Node {
        private final int value;
        private Node next;

        private Node(int value) {
            this.value = value;
        }
    }

    // The sentinel is also the monitor; it is never exposed or replaced.
    private final Node head = new Node(0);
    private int count;

    public ConcurrentSet() {
    }

    public boolean add(int value) {
        synchronized (head) {
            Node previous = findPrevious(value);
            Node current = previous.next;

            if (current != null && current.value == value) {
                return false;
            }

            Node node = new Node(value);
            node.next = current;
            previous.next = node;
            count++;
            return true;
        }
    }

    public boolean remove(int value) {
        synchronized (head) {
            Node previous = findPrevious(value);
            Node current = previous.next;

            if (current == null || current.value != value) {
                return false;
            }

            previous.next = current.next;
            count--;
            return true;
        }
    }

    public boolean contains(int value) {
        synchronized (head) {
            Node current = findPrevious(value).next;
            return current != null && current.value == value;
        }
    }

    public int size() {
        synchronized (head) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (head) {
            return head.next == null;
        }
    }

    public void clear() {
        synchronized (head) {
            head.next = null;
            count = 0;
        }
    }

    /**
     * Returns an independent, consistent snapshot of the set.
     */
    public int[] toArray() {
        synchronized (head) {
            int[] values = new int[count];
            Node current = head.next;
            int index = 0;

            while (current != null) {
                values[index++] = current.value;
                current = current.next;
            }

            return values;
        }
    }

    // Called only while holding the head monitor.
    private Node findPrevious(int value) {
        Node previous = head;

        while (previous.next != null && previous.next.value < value) {
            previous = previous.next;
        }

        return previous;
    }
}
