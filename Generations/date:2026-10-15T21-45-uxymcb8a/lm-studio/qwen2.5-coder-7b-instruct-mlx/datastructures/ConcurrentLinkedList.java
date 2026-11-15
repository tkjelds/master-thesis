@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;

    public ConcurrentLinkedList() {
        this.head = new Node<>(null);
        this.tail = head;
    }

    public synchronized boolean isEmpty() {
        return head == tail && tail.value == null;
    }

    public synchronized int size() {
        int count = 0;
        for (Node<T> node = head; node != null; node = node.next) {
            count++;
        }
        return count;
    }

    public synchronized void add(T value) {
        Node<T> newNode = new Node<>(value);
        tail.next = newNode;
        tail = newNode;
    }

    public synchronized T remove() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Node<T> headNode = head;
        head = head.next;
        if (head == null) {
            tail = null;
        }
        notifyAll();
        return headNode.value;
    }

    public synchronized T peek() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        return head.next.value;
    }

    public synchronized boolean contains(T value) {
        for (Node<T> node = head.next; node != null; node = node.next) {
            if (node.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        head = new Node<>(null);
        tail = head;
        notifyAll();
    }
}
