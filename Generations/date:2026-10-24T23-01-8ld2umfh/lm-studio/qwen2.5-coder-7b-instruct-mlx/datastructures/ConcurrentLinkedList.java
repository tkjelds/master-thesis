@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> next;
        Node(T item, Node<T> next) {
            this.item = item;
            this.next = next;
        }
    }

    private Node<T> head;
    private int size;

    public synchronized void add(T item) {
        Node<T> newNode = new Node<>(item, null);
        if (head == null) {
            head = newNode;
        } else {
            Node<T> current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
        size++;
    }

    public synchronized boolean remove(T item) {
        if (head == null) {
            return false;
        }
        Node<T> current = head;
        if (current.item.equals(item)) {
            head = current.next;
            size--;
            return true;
        }
        while (current.next != null) {
            if (current.next.item.equals(item)) {
                current.next = current.next.next;
                size--;
                return true;
            }
            current = current.next;
        }
        return false;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        Node<T> current = head;
        for (int i = 0; i < index; i++) {
            current = current.next;
        }
        return current.item;
    }

    public synchronized int size() {
        return size;
    }
}
