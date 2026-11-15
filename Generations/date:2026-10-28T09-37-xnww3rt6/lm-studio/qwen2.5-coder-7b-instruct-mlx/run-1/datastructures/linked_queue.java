@LinkedQueue.ThreadSafe
class LinkedQueue {
    @interface ThreadSafe {}

    private static class Node<T> {
        T item;
        Node<T> next;

        Node(T item) { this.item = item; }
    }

    private final Node<Object> sentinel;
    private int size;

    public LinkedQueue() {
        sentinel = new Node<>(null);
        sentinel.next = sentinel;
        size = 0;
    }

    public synchronized void enqueue(Object item) {
        Node<Object> newNode = new Node<>(item);
        Node<Object> last = sentinel;
        while (last.next != sentinel) {
            last = last.next;
        }
        newNode.next = sentinel;
        last.next = newNode;
        size++;
        notifyAll();
    }

    public synchronized Object dequeue() throws InterruptedException {
        while (isEmpty()) {
            wait();
        }
        Node<Object> first = sentinel.next;
        Object item = first.item;
        sentinel.next = first.next;
        size--;
        return item;
    }

    public synchronized int size() {
        return size;
    }

    private synchronized boolean isEmpty() {
        return sentinel.next == sentinel;
    }
}
