@LinkedQueue.ThreadSafe
public final class LinkedQueue<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E value;
        private Node<E> next;

        private Node(E value) {
            this.value = value;
        }
    }

    private Node<T> head;
    private Node<T> tail;
    private long size;

    public LinkedQueue() {
        head = new Node<>(null);
        tail = head;
    }

    public void enqueue(T value) {
        Node<T> node = new Node<>(value);
        synchronized (this) {
            tail.next = node;
            tail = node;
            size++;
            notifyAll();
        }
    }

    public T dequeue() throws InterruptedException {
        synchronized (this) {
            while (head.next == null) {
                wait();
            }

            Node<T> first = head.next;
            head = first;
            T value = first.value;
            first.value = null;
            size--;
            return value;
        }
    }

    public boolean isEmpty() {
        synchronized (this) {
            return size == 0;
        }
    }

    public long size() {
        synchronized (this) {
            return size;
        }
    }
}
