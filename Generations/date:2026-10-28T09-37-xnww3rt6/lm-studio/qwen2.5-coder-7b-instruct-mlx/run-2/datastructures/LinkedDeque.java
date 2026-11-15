@LinkedDeque.ThreadSafe
public class LinkedDeque {
    @interface ThreadSafe {}

    private static class Node {
        int value;
        Node prev;
        Node next;

        Node(int value) {
            this.value = value;
            this.prev = null;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public LinkedDeque() {
        this.head = new Node(0);
        this.tail = new Node(0);
        this.head.next = tail;
        this.tail.prev = head;
        this.size = 0;
    }

    public synchronized void addFirst(int value) {
        Node newNode = new Node(value);
        newNode.next = head.next;
        newNode.prev = head;
        head.next.prev = newNode;
        head.next = newNode;
        size++;
    }

    public synchronized int removeFirst() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        Node first = head.next;
        int value = first.value;
        first.prev.next = first.next;
        first.next.prev = first.prev;
        size--;
        notifyAll();
        return value;
    }

    public synchronized void addLast(int value) {
        Node newNode = new Node(value);
        newNode.next = tail;
        newNode.prev = tail.prev;
        tail.prev.next = newNode;
        tail.prev = newNode;
        size++;
    }

    public synchronized int removeLast() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        Node last = tail.prev;
        int value = last.value;
        last.prev.next = last.next;
        last.next.prev = last.prev;
        size--;
        notifyAll();
        return value;
    }

    public synchronized int size() {
        return size;
    }
}
