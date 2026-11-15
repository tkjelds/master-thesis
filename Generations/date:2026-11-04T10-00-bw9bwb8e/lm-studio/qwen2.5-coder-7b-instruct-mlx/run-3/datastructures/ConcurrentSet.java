@ConcurrentSet.ThreadSafe
public class ConcurrentSet {
    @interface ThreadSafe {}

    private final Node head;
    private int size;

    public ConcurrentSet() {
        head = new Node(null);
        size = 0;
    }

    public synchronized boolean add(Integer element) {
        if (contains(element)) {
            return false;
        }
        Node pred = head;
        while (true) {
            Node curr = pred.next;
            if (curr == null || curr.value.compareTo(element) >= 0) {
                Node newNode = new Node(element);
                if (pred.next == curr) {
                    pred.next = newNode;
                    size++;
                    notifyAll();
                    return true;
                }
            } else {
                pred = curr;
            }
        }
    }

    public synchronized boolean remove(Integer element) {
        Node pred = head;
        while (true) {
            Node curr = pred.next;
            if (curr == null || curr.value.compareTo(element) > 0) {
                break;
            } else if (curr.value.equals(element)) {
                if (pred.next == curr) {
                    pred.next = curr.next;
                    size--;
                    notifyAll();
                    return true;
                }
            } else {
                pred = curr;
            }
        }
        return false;
    }

    public synchronized boolean contains(Integer element) {
        Node curr = head.next;
        while (curr != null) {
            if (curr.value.equals(element)) {
                return true;
            }
            curr = curr.next;
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }

    private static class Node implements Comparable<Node> {
        Integer value;
        Node next;

        public Node(Integer value) {
            this.value = value;
        }

        @Override
        public int compareTo(Node other) {
            if (other == null || value == null) {
                throw new NullPointerException();
            }
            return value.compareTo(other.value);
        }
    }
}
