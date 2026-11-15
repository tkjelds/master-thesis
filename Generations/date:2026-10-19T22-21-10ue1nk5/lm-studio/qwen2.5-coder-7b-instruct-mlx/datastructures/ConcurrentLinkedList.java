@LinkedListNode.ThreadSafe
class LinkedListNode {
    @interface ThreadSafe {}

    int value;
    LinkedListNode next;

    public LinkedListNode(int value) {
        this.value = value;
        this.next = null;
    }
}

@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList {
    @interface ThreadSafe {}

    private LinkedListNode head;

    public synchronized void add(int value) {
        LinkedListNode newNode = new LinkedListNode(value);
        if (head == null) {
            head = newNode;
            notifyAll();
        } else {
            LinkedListNode current = head;
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
        int value = head.value;
        head = head.next;
        notifyAll();
        return value;
    }

    public synchronized boolean contains(int value) {
        LinkedListNode current = head;
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
        LinkedListNode current = head;
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
