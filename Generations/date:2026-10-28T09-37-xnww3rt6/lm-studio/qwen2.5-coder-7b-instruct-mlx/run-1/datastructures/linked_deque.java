@LinkedDeque.ThreadSafe
class LinkedDeque<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> prev, next;

        Node(T item) { this.item = item; }
    }

    private int size;
    private Node<T> head, tail;

    public LinkedDeque() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
    }

    public synchronized boolean addFirst(T item) {
        if (item == null) throw new NullPointerException();
        addFirst(item, head, head.next);
        return true;
    }

    private synchronized void addFirst(T item, Node<T> pred, Node<T> succ) {
        Node<T> newNode = new Node<>(item);
        newNode.prev = pred;
        newNode.next = succ;
        pred.next = newNode;
        succ.prev = newNode;
        size++;
        notifyAll();
    }

    public synchronized boolean addLast(T item) {
        if (item == null) throw new NullPointerException();
        addLast(item, tail.prev, tail);
        return true;
    }

    private synchronized void addLast(T item, Node<T> pred, Node<T> succ) {
        Node<T> newNode = new Node<>(item);
        newNode.prev = pred;
        newNode.next = succ;
        pred.next = newNode;
        succ.prev = newNode;
        size++;
        notifyAll();
    }

    public synchronized T removeFirst() {
        while (head.next == tail) {
            try { wait(); } catch (InterruptedException e) {}
        }
        return removeFirst(head.next);
    }

    private synchronized T removeFirst(Node<T> node) {
        Node<T> next = node.next;
        T item = node.item;
        node.item = null;
        node.next = null;
        next.prev = null;
        node = null;
        size--;
        notifyAll();
        return item;
    }

    public synchronized T removeLast() {
        while (tail.prev == head) {
            try { wait(); } catch (InterruptedException e) {}
        }
        return removeLast(tail.prev);
    }

    private synchronized T removeLast(Node<T> node) {
        Node<T> prev = node.prev;
        T item = node.item;
        node.item = null;
        node.prev = null;
        prev.next = null;
        node = null;
        size--;
        notifyAll();
        return item;
    }

    public synchronized T getFirst() {
        while (head.next == tail) {
            try { wait(); } catch (InterruptedException e) {}
        }
        return getFirst(head.next);
    }

    private synchronized T getFirst(Node<T> node) {
        return node.item;
    }

    public synchronized T getLast() {
        while (tail.prev == head) {
            try { wait(); } catch (InterruptedException e) {}
        }
        return getLast(tail.prev);
    }

    private synchronized T getLast(Node<T> node) {
        return node.item;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return (head.next == tail);
    }
}
