@LinkedQueue.ThreadSafe
public class LinkedQueue<T> {
    @interface ThreadSafe {}

    private Node head;
    private Node tail;
    private int count;
    private final int capacity;

    public LinkedQueue(int capacity) {
        this.capacity = capacity;
        this.count = 0;
    }

    public void put(T item) throws InterruptedException {
        synchronized (this) {
            while (count == capacity) {
                (this).wait();
            }
            Node newNode = new Node(item);
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            count++;
            (this).notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                (this).wait();
            }
            T item = head.data;
            head = head.next;
            if (head == null) {
                tail = null;
            }
            count--;
            (this).notifyAll();
            return item;
        }
    }

    private class Node {
        T data;
        Node next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }
}
