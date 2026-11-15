@LinkedQueue.ThreadSafe
public class LinkedQueue {
    @interface ThreadSafe {}

    private static class Node {
        final Object item;
        Node next;

        Node(Object item, Node next) {
            this.item = item;
            this.next = next;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public LinkedQueue() {
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public void enqueue(Object item) {
        Node newNode = new Node(item, null);
        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
        }
    }

    public Object dequeue() {
        synchronized (this) {
            while (head == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Dequeue interrupted", e);
                }
            }

            Object item = head.item;
            Node nextNode = head.next;

            if (nextNode == null) {
                tail = null;
            } else {
                head.next = nextNode;
            }

            head = nextNode;
            size--;
            notifyAll();
            return item;
        }
    }

    public Object peek() {
        synchronized (this) {
            while (head == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Peek interrupted", e);
                }
            }

            return head.item;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }
}
