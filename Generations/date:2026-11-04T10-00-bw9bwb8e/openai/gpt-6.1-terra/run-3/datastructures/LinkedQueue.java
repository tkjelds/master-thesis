/**
 * A thread-safe linked FIFO queue.
 *
 * Null elements are not permitted. dequeue(), poll(), and peek() return null
 * when the queue is empty; take() waits until an element is available.
 * The maximum size is Integer.MAX_VALUE. At that limit, offer() returns false
 * and enqueue() throws IllegalStateException.
 */
@LinkedQueue.ThreadSafe
public final class LinkedQueue<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object lock = new Object();
    private Node<E> head;
    private Node<E> tail;
    private int count;

    public void enqueue(E value) {
        if (!offer(value)) {
            throw new IllegalStateException("Queue is full");
        }
    }

    public boolean offer(E value) {
        if (value == null) {
            throw new NullPointerException("Null elements are not permitted");
        }

        synchronized (lock) {
            if (count == Integer.MAX_VALUE) {
                return false;
            }

            Node<E> node = new Node<E>(value);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    public E dequeue() {
        synchronized (lock) {
            return head == null ? null : removeHead();
        }
    }

    public E poll() {
        return dequeue();
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return removeHead();
        }
    }

    public E peek() {
        synchronized (lock) {
            return head == null ? null : head.value;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return head == null;
        }
    }

    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            count = 0;
        }
    }

    // Called only while holding lock and when head is non-null.
    private E removeHead() {
        Node<E> node = head;
        head = node.next;
        node.next = null;
        count--;
        if (head == null) {
            tail = null;
        }
        return node.value;
    }
}
