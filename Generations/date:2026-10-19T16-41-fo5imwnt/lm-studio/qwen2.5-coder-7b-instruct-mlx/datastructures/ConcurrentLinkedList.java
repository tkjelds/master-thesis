@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T data;
        Node<T> next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node<T> head;

    public ConcurrentLinkedList() {
        this.head = null;
    }

    public void add(T data) {
        Node<T> newNode = new Node<>(data);
        synchronized (this) {
            if (head == null) {
                head = newNode;
            } else {
                Node<T> current = head;
                while (current.next != null) {
                    current = current.next;
                }
                current.next = newNode;
            }
        }
    }

    public boolean remove(T data) {
        synchronized (this) {
            Node<T> current = head;
            Node<T> previous = null;

            while (current != null) {
                if (current.data.equals(data)) {
                    if (previous == null) {
                        head = current.next;
                    } else {
                        previous.next = current.next;
                    }
                    return true;
                }
                previous = current;
                current = current.next;
            }

            return false;
        }
    }

    public boolean contains(T data) {
        synchronized (this) {
            Node<T> current = head;
            while (current != null) {
                if (current.data.equals(data)) {
                    return true;
                }
                current = current.next;
            }

            return false;
        }
    }
}
