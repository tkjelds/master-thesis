@LinkedQueue.ThreadSafe
public class LinkedQueue {
    @interface ThreadSafe {}

    private static class Node {
        int item;
        Node next;

        Node(int item) {
            this.item = item;
        }
    }

    private final Node head;
    private volatile Node tail;
    private int count;

    public LinkedQueue() {
        this.head = new Node(0); // sentinel node
        this.tail = head;
        this.count = 0;
    }

    public boolean enqueue(int item) {
        synchronized (this) {
            Node newNode = new Node(item);
            tail.next = newNode;
            tail = newNode;
            count++;
            notifyAll();
            return true;
        }
    }

    public int dequeue() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Node node = head.next;
            int item = node.item;
            head = node;
            count--;
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }
}
