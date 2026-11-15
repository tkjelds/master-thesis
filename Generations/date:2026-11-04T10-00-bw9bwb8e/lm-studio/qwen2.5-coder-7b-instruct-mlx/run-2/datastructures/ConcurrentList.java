@ConcurrentList.ThreadSafe
public class ConcurrentList<T> {
    @interface ThreadSafe {}

    private final Node head;
    private int size;

    public ConcurrentList() {
        this.head = new Node(null);
        this.size = 0;
    }

    public boolean add(T item) {
        synchronized (head) {
            Node pred = head;
            Node current = pred.next;
            while (current != null) {
                if (current.item == null) {
                    current.item = item;
                    size++;
                    return true;
                }
                pred = current;
                current = current.next;
            }
            Node newNode = new Node(item);
            pred.next = newNode;
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        synchronized (head) {
            Node pred = head;
            Node current = pred.next;
            while (current != null) {
                if (current.item == item) {
                    pred.next = current.next;
                    current.item = null;
                    size--;
                    return true;
                }
                pred = current;
                current = current.next;
            }
            return false;
        }
    }

    public boolean contains(T item) {
        synchronized (head) {
            Node current = head.next;
            while (current != null) {
                if (current.item == item) {
                    return true;
                }
                current = current.next;
            }
            return false;
        }
    }

    public int size() {
        synchronized (head) {
            return this.size;
        }
    }

    private static class Node<T> {
        T item;
        Node next;

        Node(T item) {
            this.item = item;
            this.next = null;
        }
    }
}
