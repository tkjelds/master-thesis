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
    private int size;

    public LinkedQueue() {
        head = new Node<>(null, null);
        tail = head;
        size = 0;
    }

    public synchronized void enqueue(T item) {
        Node<T> newNode = new Node<>(item, null);
        tail.next = newNode;
        tail = newNode;
        size++;
        notifyAll();
    }

    public synchronized T dequeue() throws InterruptedException {
        while (head == tail && size == 0) {
            wait();
        }
        if (head == tail && size == 1) {
            Node<T> oldHead = head;
            head = new Node<>(null, null);
            tail = head;
            size--;
            return oldHead.item;
        } else {
            Node<T> oldHead = head.next;
            head = oldHead;
            size--;
            return oldHead.item;
        }
    }

    public synchronized int size() {
        return size;
    }
}
