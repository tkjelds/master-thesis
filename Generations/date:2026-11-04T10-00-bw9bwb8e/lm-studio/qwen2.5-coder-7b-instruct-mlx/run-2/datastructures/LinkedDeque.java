@LinkedDeque.ThreadSafe
public class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static class Node<E> {
        E item;
        Node<E> next, prev;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private Node<E> head, tail;
    private int size;

    public LinkedDeque() {
        head = new Node<>(null);
        tail = new Node<>(null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void addFirst(E item) {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            Node<E> newNode = new Node<>(item);
            newNode.next = head.next;
            newNode.prev = head;
            head.next.prev = newNode;
            head.next = newNode;
            size++;
        }
    }

    public void addLast(E item) {
        if (item == null) throw new NullPointerException();
        synchronized (lock) {
            Node<E> newNode = new Node<>(item);
            newNode.next = tail;
            newNode.prev = tail.prev;
            tail.prev.next = newNode;
            tail.prev = newNode;
            size++;
        }
    }

    public E removeFirst() {
        if (isEmpty()) throw new IllegalStateException("Deque is empty");
        synchronized (lock) {
            while (head.next == tail) { // Wait until there is an element
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
            Node<E> first = head.next;
            E item = first.item;
            first.prev = null;
            first.next = null;
            head.next = first.next;
            first.next.prev = head;
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public E removeLast() {
        if (isEmpty()) throw new IllegalStateException("Deque is empty");
        synchronized (lock) {
            while (tail.prev == head) { // Wait until there is an element
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Thread interrupted", e);
                }
            }
            Node<E> last = tail.prev;
            E item = last.item;
            last.prev = null;
            last.next = null;
            tail.prev = last.prev;
            last.prev.next = tail;
            size--;
            lock.notifyAll();
            return item;
        }
    }

    public E peekFirst() {
        if (isEmpty()) return null;
        synchronized (lock) {
            while (head.next == tail) { // Wait until there is an element
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Node<E> first = head.next;
            return first.item;
        }
    }

    public E peekLast() {
        if (isEmpty()) return null;
        synchronized (lock) {
            while (tail.prev == head) { // Wait until there is an element
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Node<E> last = tail.prev;
            return last.item;
        }
    }
}
