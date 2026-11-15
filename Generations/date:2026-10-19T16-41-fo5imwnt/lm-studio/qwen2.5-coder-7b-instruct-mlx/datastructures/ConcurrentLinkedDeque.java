@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<T> {
    @interface ThreadSafe {}

    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 16;
    
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public ConcurrentLinkedDeque() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    public synchronized void addFirst(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> newNode = new Node<>(item);
        addFirstNode(newNode);
    }

    public synchronized T removeFirst() {
        if (size == 0) return null;
        Node<T> node = head.next;
        removeNode(node);
        return node.item;
    }

    public synchronized T getFirst() {
        if (size == 0) return null;
        return head.next.item;
    }

    public synchronized void addLast(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> newNode = new Node<>(item);
        addLastNode(newNode);
    }

    public synchronized T removeLast() {
        if (size == 0) return null;
        Node<T> node = tail.prev;
        removeNode(node);
        return node.item;
    }

    public synchronized T getLast() {
        if (size == 0) return null;
        return tail.prev.item;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized void addFirstNode(Node<T> newNode) {
        Node<T> oldHead = head;
        oldHead.next = newNode;
        newNode.prev = oldHead;
        newNode.next = tail;
        tail.prev = newNode;
        size++;
    }

    private synchronized void addLastNode(Node<T> newNode) {
        Node<T> oldTail = tail;
        oldTail.prev = newNode;
        newNode.next = oldTail;
        newNode.prev = head;
        head.next = newNode;
        size++;
    }

    private synchronized void removeNode(Node<T> node) {
        Node<T> prev = node.prev;
        Node<T> next = node.next;
        next.prev = prev;
        prev.next = next;
        size--;
    }

    private static class Node<T> {
        T item;
        Node<T> prev;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }
}
