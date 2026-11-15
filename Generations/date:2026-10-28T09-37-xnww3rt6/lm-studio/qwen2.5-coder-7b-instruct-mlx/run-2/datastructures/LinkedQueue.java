@LinkedQueue.ThreadSafe
public class LinkedQueue {
    @interface ThreadSafe {}


    private static class Node<E> {
        E item;
        Node<E> next;

        Node(E item, Node<E> next) {
            this.item = item;
            this.next = next;
        }
    }

    private Node<E> head;
    private Node<E> tail;
    private int size;

    public LinkedQueue() {
        head = new Node<>(null, null);
        tail = head;
        size = 0;
    }

    public synchronized boolean enqueue(E item) {
        if (item == null) throw new NullPointerException();
        Node<E> newNode = new Node<>(item, null);
        tail.next = newNode;
        tail = newNode;
        size++;
        notifyAll();
        return true;
    }

    public synchronized E dequeue() {
        while (head.next == null) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        Node<E> oldHead = head.next;
        E item = oldHead.item;
        head.next = oldHead.next;
        if (head.next == null) {
            tail = head;
        }
        size--;
        notifyAll();
        return item;
    }

    public synchronized int size() {
        return size;
    }
}
