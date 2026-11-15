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
            while (size >= capacity) {
                lock.wait();
            }
            append(value);
            lock.notifyAll();
        }
    }

    // Non-blocking; returns false if full.
    public boolean offer(int value) {
        synchronized (lock) {
            if (size >= capacity) {
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

    public static void main(String[] args) throws Exception {
        final ConcurrentLinkedQueue q = new ConcurrentLinkedQueue(10);
        Thread p = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < 100; i++) {
                        q.enqueue(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread c = new Thread(new Runnable() {
            public void run() {
                try {
                    long sum = 0;
                    for (int i = 0; i < 100; i++) {
                        sum += q.dequeue();
                    }
                    System.out.println("sum=" + sum);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        p.start();
        c.start();
        p.join();
        c.join();
    }
}
