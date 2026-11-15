@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private static class Node {
        Object value;
        Node next;

        Node(Object val) {
            this.value = val;
        }
    }

    private Node head;
    private int size;
    private final int capacity;
    private final Object lock = new Object();

    public ConcurrentStack(int capacity) {
        this.capacity = capacity;
    }

    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Item cannot be null");
        }
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            Node newNode = new Node(item);
            newNode.next = head;
            head = newNode;
            size++;
            lock.notifyAll();
        }
    }

    public T pop() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node node = head;
            head = node.next;
            size--;
            lock.notifyAll();
            return (T) node.value;
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

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }
}
