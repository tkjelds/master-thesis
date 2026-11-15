@LinkedDeque.ThreadSafe
class LinkedDeque<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) {
            this.item = item;
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

    public synchronized void addLast(T item) {
        Node<T> newNode = new Node<>(item);
        newNode.next = tail;
        newNode.prev = tail.prev;
        tail.prev.next = newNode;
        tail.prev = newNode;
        size++;
    }

    public synchronized T removeFirst() {
        if (size == 0) throw new IllegalStateException("Deque is empty");
        Node<T> firstNode = head.next;
        T item = firstNode.item;
        firstNode.next.prev = head;
        head.next = firstNode.next;
        size--;
        return item;
    }

    public synchronized T removeLast() {
        if (size == 0) throw new IllegalStateException("Deque is empty");
        Node<T> lastNode = tail.prev;
        T item = lastNode.item;
        lastNode.prev.next = tail;
        tail.prev = lastNode.prev;
        size--;
        return item;
    }

    public synchronized T getFirst() {
        if (size == 0) throw new IllegalStateException("Deque is empty");
        return head.next.item;
    }

    public synchronized T getLast() {
        if (size == 0) throw new IllegalStateException("Deque is empty");
        return tail.prev.item;
    }

    public synchronized int size() {
        return size;
    }
}
