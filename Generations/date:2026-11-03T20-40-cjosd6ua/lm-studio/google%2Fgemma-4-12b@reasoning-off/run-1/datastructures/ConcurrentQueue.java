public class ConcurrentQueue<T> {

    private static class Node<T> {
        T data;
        Node<T> next;

        Node(T data) {
            this.data = data;
            this.next = null;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private int size;

    public ConcurrentQueue() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        size = 0;
    }

    public void enqueue(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("Cannot enqueue null");
        }

        synchronized (this) {
            Node<T> newNode = new Node<>(item);
            Node<T> currentTail = tail;
            currentTail.next = newNode;
            tail = newNode;
            size++;
            
            this.notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head.next == tail) {
                this.wait();
            }

            Node<T> first = head.next;
            T data = first.data;
            head = first.next;
            size--;

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
            return head.next == tail;
        }
    }

    public void clear() {
        synchronized (this) {
            head.next = tail;
            tail = new Node<>(null);
            head = new Node<>(null);
            head.next = tail;
            size = 0;
        }
    }
}
