@LinkedQueue.ThreadSafe
public class LinkedQueue<T> {
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
    private Node<T> tail;

    public LinkedQueue() {
        init();
    }

    public synchronized void enqueue(T item) {
        Node<T> newNode = new Node<>(item, null);
        if (tail != null) {
            tail.next = newNode;
        }
        tail = newNode;
        if (head == null) {
            head = tail;
        }
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (head == null) {
            wait();
        }
        T item = head.item;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        notifyAll();
        return item;
    }

    public synchronized boolean isEmpty() {
        return head == null;
    }

    private void init() {
        head = null;
        tail = null;
    }
}
