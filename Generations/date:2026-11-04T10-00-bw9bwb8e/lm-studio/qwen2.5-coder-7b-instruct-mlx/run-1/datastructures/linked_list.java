@Node.ThreadSafe
class Node {
    @interface ThreadSafe {}

    int value;
    Node next;

    public Node(int value) {
        this.value = value;
        this.next = null;
    }
}

@ConcurrentLinkedList.ThreadSafe
class ConcurrentLinkedList {
    @interface ThreadSafe {}

    private Node head;

    public synchronized boolean isEmpty() {
        return head == null;
    }

    public synchronized void add(int value) {
        Node newNode = new Node(value);
        if (head == null) {
            head = newNode;
        } else {
            Node current = head;
            while (current.next != null) {
                current = current.next;
            }
            current.next = newNode;
        }
    }

    public synchronized int remove() throws InterruptedException {
        while (head == null) {
            wait();
        }
        Node toRemove = head;
        head = head.next;
        notifyAll();
        return toRemove.value;
    }

    public synchronized int size() {
        int count = 0;
        Node current = head;
        while (current != null) {
            count++;
            current = current.next;
        }
        return count;
    }

    public synchronized void clear() {
        head = null;
        notifyAll();
    }
}
