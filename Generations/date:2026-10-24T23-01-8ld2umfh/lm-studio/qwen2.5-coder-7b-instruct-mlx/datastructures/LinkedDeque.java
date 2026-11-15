@LinkedDeque.ThreadSafe
public class LinkedDeque<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) {
            this.item = item;
            this.prev = null;
            this.next = null;
        }
    }

    private Node<T> head, tail;
    private int size;

    public LinkedDeque() {
        this.head = new Node<>(null);
        this.tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        this.size = 0;
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
        if (size == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        Node<T> first = head.next;
        T item = first.item;
        first.next.prev = head;
        head.next = first.next;
        size--;
        return item;
    }

    public synchronized T removeLast() {
        if (size == 0) {
            throw new IllegalStateException("Deque is empty");
        }
        Node<T> last = tail.prev;
        T item = last.item;
        last.prev.next = tail;
        tail.prev = last.prev;
        size--;
        return item;
    }

    public synchronized T peekFirst() {
        if (size == 0) {
            return null;
        }
        return head.next.item;
    }

    public synchronized T peekLast() {
        if (size == 0) {
            return null;
        }
        return tail.prev.item;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }
}
