@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        final E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    public ConcurrentLinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    private void linkFirst(E e) {
        Node<E> n = new Node<E>(e);
        n.next = head;
        if (head == null) {
            tail = n;
        } else {
            head.prev = n;
        }
        head = n;
        size++;
    }

    private void linkLast(E e) {
        Node<E> n = new Node<E>(e);
        n.prev = tail;
        if (tail == null) {
            head = n;
        } else {
            tail.next = n;
        }
        tail = n;
        size++;
    }

    private E unlinkFirst() {
        Node<E> n = head;
        head = n.next;
        if (head == null) {
            tail = null;
        } else {
            head.prev = null;
        }
        n.next = null;
        size--;
        return n.item;
    }

    private E unlinkLast() {
        Node<E> n = tail;
        tail = n.prev;
        if (tail == null) {
            head = null;
        } else {
            tail.next = null;
        }
        n.prev = null;
        size--;
        return n.item;
    }

    // Blocking operations: wait while full (for add) or empty (for remove).

    public void putFirst(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(e);
            lock.notifyAll();
        }
    }

    public void putLast(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(e);
            lock.notifyAll();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E r = unlinkFirst();
            lock.notifyAll();
            return r;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E r = unlinkLast();
            lock.notifyAll();
            return r;
        }
    }

    // Non-blocking operations: return false / null when they cannot complete.

    public boolean offerFirst(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size == capacity) return false;
            linkFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E e) {
        if (e == null) throw new NullPointerException();
        synchronized (lock) {
            if (size == capacity) return false;
            linkLast(e);
            lock.notifyAll();
            return true;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) return null;
            E r = unlinkFirst();
            lock.notifyAll();
            return r;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) return null;
            E r = unlinkLast();
            lock.notifyAll();
            return r;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    // Convenience aliases.

    public void addFirst(E e) throws InterruptedException {
        putFirst(e);
    }

    public void addLast(E e) throws InterruptedException {
        putLast(e);
    }

    public E removeFirst() throws InterruptedException {
        return takeFirst();
    }

    public E removeLast() throws InterruptedException {
        return takeLast();
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

    public int capacity() {
        return capacity;
    }

    public void clear() {
        synchronized (lock) {
            Node<E> n = head;
            while (n != null) {
                Node<E> next = n.next;
                n.prev = null;
                n.next = null;
                n = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }
}
