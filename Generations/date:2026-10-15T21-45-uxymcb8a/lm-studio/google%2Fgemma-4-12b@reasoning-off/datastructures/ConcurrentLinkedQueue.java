@ConcurrentLinkedQueue.ThreadSafe
public class ConcurrentLinkedQueue<T> {
    @interface ThreadSafe {}


    private class Node {
        T value;
        Node next;

        Node(T value) {
            this.value = value;
            this.next = null;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public ConcurrentLinkedQueue() {
        head = null;
        tail = null;
        size = 0;
    }

    public void enqueue(T value) {
        if (value == null) {
            throw new NullPointerException("Cannot enqueue null value");
        }
        Node newNode = new Node(value);

        synchronized (this) {
            if (tail == null) {
                head = newNode;
                tail = newNode;
            } else {
                tail.next = newNode;
                tail = newNode;
            }
            size++;
            this.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                this.wait();
            }

            Node first = head;
            T value = first.value;
            head = head.next;

            if (head == null) {
                tail = null;
            } else {
                tail.next = null;
            }

            size--;
            return value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return head == null;
        }
    }

    public boolean isNotEmpty() {
        synchronized (this) {
            return head != null;
        }
    }
}
