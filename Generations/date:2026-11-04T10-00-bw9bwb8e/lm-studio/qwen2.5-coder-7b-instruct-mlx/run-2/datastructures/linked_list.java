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

    public synchronized boolean remove(int value) {
        if (head == null) {
            return false;
        }
        if (head.value == value) {
            head = head.next;
            return true;
        }
        Node current = head;
        while (current.next != null) {
            if (current.next.value == value) {
                current.next = current.next.next;
                return true;
            }
            current = current.next;
        }
        return false;
    }

    public synchronized boolean contains(int value) {
        Node current = head;
        while (current != null) {
            if (current.value == value) {
                return true;
            }
            current = current.next;
        }
        return false;
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
    }
}
