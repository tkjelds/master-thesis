@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        T item;
        Node<T> previous;
        Node<T> next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    /** Creates an unbounded deque. */
    public ConcurrentLinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    /** Creates a deque with the given positive capacity. */
    public ConcurrentLinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /** Attempts to add an item at the front; returns false if the deque is full. */
    public boolean offerFirst(E item) {
        checkItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Attempts to add an item at the back; returns false if the deque is full. */
    public boolean offerLast(E item) {
        checkItem(item);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(item);
            lock.notifyAll();
            return true;
        }
    }

    /** Adds an item at the front, waiting while the deque is full. */
    public void putFirst(E item) throws InterruptedException {
        checkItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(item);
            lock.notifyAll();
        }
    }

    /** Adds an item at the back, waiting while the deque is full. */
    public void putLast(E item) throws InterruptedException {
        checkItem(item);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkLast(item);
            lock.notifyAll();
        }
    }

    /** Adds an item at the front, or throws if the deque is full. */
    public void addFirst(E item) {
        if (!offerFirst(item)) {
            throw new IllegalStateException("deque is full");
        }
    }

    /** Adds an item at the back, or throws if the deque is full. */
    public void addLast(E item) {
        if (!offerLast(item)) {
            throw new IllegalStateException("deque is full");
        }
    }

    /** Removes and returns the front item, or null if the deque is empty. */
    public E pollFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the back item, or null if the deque is empty. */
    public E pollLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the front item, waiting while the deque is empty. */
    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the back item, waiting while the deque is empty. */
    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (tail == null) {
                lock.wait();
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the front item, or throws if the deque is empty. */
    public E removeFirst() {
        synchronized (lock) {
            if (head == null) {
                throw new IllegalStateException("deque is empty");
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    /** Removes and returns the back item, or throws if the deque is empty. */
    public E removeLast() {
        synchronized (lock) {
            if (tail == null) {
                throw new IllegalStateException("deque is empty");
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    /** Returns the front item, or null if the deque is empty. */
    public E peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    /** Returns the back item, or null if the deque is empty. */
    public E peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
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

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    /** Removes all items and wakes threads waiting to add items. */
    public void clear() {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.item = null;
                current.previous = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    private void linkFirst(E item) {
        Node<E> node = new Node<E>(item);
        Node<E> oldHead = head;
        node.next = oldHead;
        if (oldHead == null) {
            tail = node;
        } else {
            oldHead.previous = node;
        }
        head = node;
        size++;
    }

    private void linkLast(E item) {
        Node<E> node = new Node<E>(item);
        Node<E> oldTail = tail;
        node.previous = oldTail;
        if (oldTail == null) {
            head = node;
        } else {
            oldTail.next = node;
        }
        tail = node;
        size++;
    }

    private E unlinkFirst() {
        Node<E> node = head;
        Node<E> next = node.next;
        head = next;
        if (next == null) {
            tail = null;
        } else {
            next.previous = null;
        }
        size--;
        E item = node.item;
        node.item = null;
        node.next = null;
        node.previous = null;
        return item;
    }

    private E unlinkLast() {
        Node<E> node = tail;
        Node<E> previous = node.previous;
        tail = previous;
        if (previous == null) {
            head = null;
        } else {
            previous.next = null;
        }
        size--;
        E item = node.item;
        node.item = null;
        node.next = null;
        node.previous = null;
        return item;
    }

    private void checkItem(E item) {
        if (item == null) {
            throw new IllegalArgumentException("null items are not supported");
        }
    }
}
