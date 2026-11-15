@ConcurrentLinkedQueue.ThreadSafe
public class ConcurrentLinkedQueue {
    @interface ThreadSafe {}


    private static final class Node {
        final int value;
        Node next;

        Node(int value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node head;
    private Node tail;
    private int size;
    private final int capacity;

    public ConcurrentLinkedQueue() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    // Blocks while the queue is full.
    public void enqueue(int value) throws InterruptedException {
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            append(value);
            lock.notifyAll();
        }
    }

    // Non-blocking; returns false if full.
    public boolean offer(int value) {
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            append(value);
            lock.notifyAll();
            return true;
        }
    }

    // Blocks while the queue is empty.
    public int dequeue() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            int v = removeHead();
            lock.notifyAll();
            return v;
        }
    }

    // Non-blocking; returns null if empty.
    public Integer poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            int v = removeHead();
            lock.notifyAll();
            return v;
        }
    }

    // Non-blocking; returns null if empty.
    public Integer peek() {
        synchronized (lock) {
            return head == null ? null : Integer.valueOf(head.value);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    // Must be called while holding lock.
    private void append(int value) {
        Node n = new Node(value);
        if (tail == null) {
            head = n;
            tail = n;
        } else {
            tail.next = n;
            tail = n;
        }
        size++;
    }

    // Must be called while holding lock.
    private int removeHead() {
        Node n = head;
        head = n.next;
        if (head == null) {
            tail = null;
        }
        n.next = null;
        size--;
        return n.value;
    }
}
