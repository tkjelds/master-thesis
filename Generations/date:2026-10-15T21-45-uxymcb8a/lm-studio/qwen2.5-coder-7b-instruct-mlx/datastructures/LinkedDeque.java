@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static class Node<E> {
        E item;
        Node<E> prev, next;

        Node(E item) {
            this.item = item;
            prev = next = null;
        }
    }

    private Node<E> head, tail;
    private int size;

    public LinkedDeque() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    public synchronized void addFirst(E item) {
        if (item == null) throw new NullPointerException();
        Node<E> newNode = new Node<>(item);
        newNode.next = head.next;
        newNode.prev = head;
        newNode.next.prev = newNode;
        head.next = newNode;
        size++;
    }

    public synchronized E removeFirst() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Node<E> node = head.next;
        unlink(node);
        return node.item;
    }

    public synchronized void addLast(E item) {
        if (item == null) throw new NullPointerException();
        Node<E> newNode = new Node<>(item);
        newNode.prev = tail.prev;
        newNode.next = tail;
        newNode.prev.next = newNode;
        tail.prev = newNode;
        size++;
    }

    public synchronized E removeLast() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Node<E> node = tail.prev;
        unlink(node);
        return node.item;
    }

    public synchronized E peekFirst() {
        Node<E> first = head.next;
        return (first == tail) ? null : first.item;
    }

    public synchronized E peekLast() {
        Node<E> last = tail.prev;
        return (last == head) ? null : last.item;
    }

    public synchronized boolean isEmpty() {
        return (head.next == tail);
    }

    private void unlink(Node<E> node) {
        Node<E> prev = node.prev;
        Node<E> next = node.next;
        prev.next = next;
        next.prev = prev;
        size--;
    }

    public synchronized int size() {
        return size;
    }
}
