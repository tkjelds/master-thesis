@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Node<T> sentinel;
    private int size;

    public ConcurrentLinkedList() {
        sentinel = new Node<>(null);
        sentinel.next = sentinel;
        size = 0;
    }

    public synchronized void add(T value) {
        Node<T> newNode = new Node<>(value);
        Node<T> tail = sentinel;
        while (tail.next != sentinel) {
            tail = tail.next;
        }
        newNode.next = sentinel;
        tail.next = newNode;
        size++;
    }

    public synchronized boolean remove(T value) {
        Node<T> pred = sentinel;
        while (pred.next != sentinel) {
            if (pred.next.value.equals(value)) {
                pred.next = pred.next.next;
                size--;
                return true;
            }
            pred = pred.next;
        }
        return false;
    }

    public synchronized boolean contains(T value) {
        Node<T> pred = sentinel;
        while (pred.next != sentinel) {
            if (pred.next.value.equals(value)) {
                return true;
            }
            pred = pred.next;
        }
        return false;
    }

    public synchronized int size() {
        return size;
    }
}
