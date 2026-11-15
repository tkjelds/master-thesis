@LinkedQueue.ThreadSafe
class LinkedQueue {
    @interface ThreadSafe {}

    private static class Node {
        int item;
        Node next;

        Node(int item) {
            this.item = item;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public LinkedQueue() {
        head = new Node(-1);
        tail = head;
        size = 0;
    }

    public synchronized void enqueue(int item) {
        Node newNode = new Node(item);
        tail.next = newNode;
        tail = newNode;
        size++;
        notifyAll();
    }

    public synchronized int dequeue() throws InterruptedException {
        while (size == 0) {
            wait();
        }
        int item = head.next.item;
        head = head.next;
        size--;
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
