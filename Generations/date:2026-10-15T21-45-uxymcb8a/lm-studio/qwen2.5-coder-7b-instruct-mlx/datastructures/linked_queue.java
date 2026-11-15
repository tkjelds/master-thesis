@LinkedQueue.ThreadSafe
class LinkedQueue {
    @interface ThreadSafe {}

    private static class Node {
        int value;
        Node next;

        Node(int value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;

    public LinkedQueue() {
        this.head = null;
        this.tail = null;
    }

    public void enqueue(int value) {
        Node newNode = new Node(value);
        synchronized (this) {
            if (tail != null) {
                tail.next = newNode;
            }
            tail = newNode;
            if (head == null) {
                head = newNode;
            }
            notifyAll();
        }
    }

    public int dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                wait();
            }
            int value = head.value;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            return value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }

    public int size() {
        synchronized (this) {
            int count = 0;
            Node current = head;
            while (current != null) {
                count++;
                current = current.next;
            }
            return count;
        }
    }
}
