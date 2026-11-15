@ConcurrentLinkedQueue.ThreadSafe
public class ConcurrentLinkedQueue {
    @interface ThreadSafe {}


    private static final class Node {
        final Object value;
        Node next;

        Node(Object value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node head; // first real node, or null if empty
    private Node tail;
    private int size;
    private final int capacity; // Integer.MAX_VALUE means unbounded

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
    public void put(Object value) throws InterruptedException {
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            enqueueLocked(value);
            lock.notifyAll();
        }
    }

    // Alias for put (blocking when bounded and full).
    public void enqueue(Object value) throws InterruptedException {
        put(value);
    }

    // Non-blocking insert; returns false if full.
    public boolean offer(Object value) {
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            enqueueLocked(value);
            lock.notifyAll();
            return true;
        }
    }

    // Blocks while the queue is empty.
    public Object take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Object v = dequeueLocked();
            lock.notifyAll();
            return v;
        }
    }

    public Object dequeue() throws InterruptedException {
        return take();
    }

    // Non-blocking remove; returns null if empty.
    public Object poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Object v = dequeueLocked();
            lock.notifyAll();
            return v;
        }
    }

    // Returns the head without removing it, or null if empty.
    public Object peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity == Integer.MAX_VALUE ? Integer.MAX_VALUE : capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    // Must be called while holding lock.
    private void enqueueLocked(Object value) {
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

    // Must be called while holding lock and size > 0.
    private Object dequeueLocked() {
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
