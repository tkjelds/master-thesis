@ConcurrentStack.ThreadSafe
public class ConcurrentStack<T> {
    @interface ThreadSafe {}

    private Node<T> top;
    private int size;

    public synchronized boolean push(T item) {
        Node<T> newNode = new Node<>(item);
        newNode.next = top;
        top = newNode;
        size++;
        notifyAll();
        return true;
    }

    public synchronized T pop() throws InterruptedException {
        while (top == null) {
            wait();
        }
        T item = top.item;
        top = top.next;
        size--;
        return item;
    }

    public synchronized T peek() throws InterruptedException {
        while (top == null) {
            wait();
        }
        return top.item;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    private static class Node<T> {
        T item;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }
}
