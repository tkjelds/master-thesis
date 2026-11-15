@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}


    private static class Node<T> {
        private final T data;
        private Node<T> next;

        Node(T data) {
            this.data = data;
        }
    }

    private Node<T> head;
    private int size;
    private final int capacity;

    public ConcurrentStack(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        this.capacity = capacity;
        this.head = null;
        this.size = 0;
    }

    public void push(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot push null");
        }

        synchronized (this) {
            while (size == capacity) {
                this.wait();
            }

            Node<T> newNode = new Node<>(item);
            newNode.next = head;
            head = newNode;
            size++;

            this.notifyAll();
        }
    }

    public T pop() throws InterruptedException {
        synchronized (this) {
            while (head == null) {
                this.wait();
            }

            Node<T> removedNode = head;
            head = removedNode.next;
            size--;

            T data = removedNode.data;
            this.notifyAll();
            return data;
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

    public boolean isFull() {
        synchronized (this) {
            return size == capacity;
        }
    }

    public int getCapacity() {
        return capacity;
    }
}
