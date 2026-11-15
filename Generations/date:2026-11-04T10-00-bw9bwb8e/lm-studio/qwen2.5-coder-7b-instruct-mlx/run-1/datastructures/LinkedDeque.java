@LinkedDeque.ThreadSafe
public class LinkedDeque<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) {
            this.item = item;
            prev = null;
            next = null;
        }
    }

    private Node<T> head, tail;
    private int size;

    public LinkedDeque() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    public synchronized void addFirst(T item) {
        Node<T> newNode = new Node<>(item);
        newNode.next = head.next;
        newNode.prev = head;
        head.next.prev = newNode;
        head.next = newNode;
        size++;
    }

    public synchronized T removeFirst() {
        if (isEmpty()) return null;
        Node<T> oldHead = head.next;
        T item = oldHead.item;
        oldHead.item = null;
        oldHead.prev.next = oldHead.next;
        oldHead.next.prev = oldHead.prev;
        size--;
        return item;
    }

    public synchronized void addLast(T item) {
        Node<T> newNode = new Node<>(item);
        newNode.next = tail;
        newNode.prev = tail.prev;
        tail.prev.next = newNode;
        tail.prev = newNode;
        size++;
    }

    public synchronized T removeLast() {
        if (isEmpty()) return null;
        Node<T> oldTail = tail.prev;
        T item = oldTail.item;
        oldTail.item = null;
        oldTail.next.prev = oldTail.prev;
        oldTail.prev.next = oldTail.next;
        size--;
        return item;
    }

    public synchronized int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
