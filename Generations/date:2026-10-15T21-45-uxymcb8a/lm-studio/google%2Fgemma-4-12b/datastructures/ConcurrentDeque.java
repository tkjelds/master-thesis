@ConcurrentDeque.ThreadSafe
public class ConcurrentDeque<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T data;
        Node<T> prev;
        Node<T> next;

        Node(T item) {
            this.data = item;
        }
    }

    private final Node<T> head;
    private final Node<T> tail;
    private int count;
    private final int capacity;

    public ConcurrentDeque(int capacity) {
        this.capacity = capacity;
        this.head = new Node<>();
        this.tail = new Node<>();
        this.head.next = tail;
        this.tail.prev = head;
        this.count = 0;
    }

    public void putFirst(T item) throws InterruptedException {
        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            Node<T> node = new Node<>(item);
            node.prev = head;
            node.next = head.next;
            head.next.prev = node;
            head.next = node;
            count++;
            notifyAll();
        }
    }

    public void putLast(T item) throws InterruptedException {
        synchronized (this) {
            while (count == capacity) {
                wait();
            }
            Node<T> node = new Node<>(item);
            node.prev = tail.prev;
            node.next = tail;
            tail.prev.next = node;
            tail.prev = node;
            count++;
            notifyAll();
        }
    }

    public T takeFirst() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Node<T> first = head.next;
            T item = (T) (java.lang.Object) first.data;
            head.next = first.next;
            first.next.prev = head;
            count--;
            notifyAll();
            return item;
        }
    }

    public T takeLast() throws InterruptedException {
        synchronized (this) {
            while (count == 0) {
                wait();
            }
            Node<T> last = tail.prev;
            T item = (T) (java.lang.Object) last.data;
            tail.prev = last.prev;
            last.prev.next = tail;
            count--;
            notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return count == 0;
        }
    }

    public boolean isFull() {
        synchronized (this) {
            return count == capacity;
        }
    }
}
