@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;

    public ConcurrentLinkedList() {
        this.head = new Node<>(null);
        this.tail = head;
    }

    public synchronized void add(T value) {
        Node<T> newNode = new Node<>(value);
        tail.next = newNode;
        tail = newNode;
    }

    public synchronized boolean remove(T value) {
        Node<T> current = head;
        while (current.next != null) {
            if (current.next.value.equals(value)) {
                current.next = current.next.next;
                return true;
            }
            current = current.next;
        }
        return false;
    }

    public synchronized T get(int index) {
        Node<T> current = head.next;
        int i = 0;
        while (current != null) {
            if (i == index) {
                return current.value;
            }
            i++;
            current = current.next;
        }
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
    }

    public synchronized int size() {
        Node<T> current = head.next;
        int count = 0;
        while (current != null) {
            count++;
            current = current.next;
        }
        return count;
    }

    public synchronized void clear() {
        head.next = null;
        tail = head;
    }
}
