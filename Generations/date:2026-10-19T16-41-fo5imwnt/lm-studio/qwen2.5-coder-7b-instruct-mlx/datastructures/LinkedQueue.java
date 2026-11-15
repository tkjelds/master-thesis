@LinkedQueue.ThreadSafe
public class LinkedQueue<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;

    public LinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    public synchronized void enqueue(T item) {
        Node<T> newNode = new Node<>(item);
        if (tail == null) {
            head = tail = newNode;
        } else {
            tail.next = newNode;
            tail = newNode;
        }
        size++;
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
        size--;
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
