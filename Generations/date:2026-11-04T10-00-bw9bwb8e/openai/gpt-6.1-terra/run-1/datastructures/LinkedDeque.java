/**
 * A thread-safe linked deque with an optional capacity.
 *
 * Null elements are not permitted. offer methods return false when full;
 * add and push methods throw IllegalStateException when full; put methods
 * wait for space. poll and peek methods return null when empty; remove,
 * pop, element, and get methods throw IllegalStateException when empty;
 * take methods wait for an element.
 */
@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        final T value;
        Node<T> previous;
        Node<T> next;

        Node(T value) {
            this.value = value;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    /** Creates a deque with a maximum capacity of 2147483647. */
    public LinkedDeque() {
        this(2147483647);
    }

    /** Creates a deque with the given positive capacity. */
    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
    }

    private void checkElement(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // The following four helpers must be called while holding monitor.
    private void linkFirst(E element) {
        Node<E> node = new Node<E>(element);
        node.next = first;
        if (first == null) {
            last = node;
        } else {
            first.previous = node;
        }
        first = node;
        size++;
        monitor.notifyAll();
    }

    private void linkLast(E element) {
        Node<E> node = new Node<E>(element);
        node.previous = last;
        if (last == null) {
            first = node;
        } else {
            last.next = node;
        }
        last = node;
        size++;
        monitor.notifyAll();
    }

    private E unlinkFirst() {
        Node<E> node = first;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        node.next = null;
        size--;
        monitor.notifyAll();
        return node.value;
    }

    private E unlinkLast() {
        Node<E> node = last;
        last = node.previous;
        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }
        node.previous = null;
        size--;
        monitor.notifyAll();
        return node.value;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int remainingCapacity() {
        synchronized (monitor) {
            return capacity - size;
        }
    }

    public boolean offerFirst(E element) {
        checkElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkFirst(element);
            return true;
        }
    }

    public boolean offerLast(E element) {
        checkElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkLast(element);
            return true;
        }
    }

    public void addFirst(E element) {
        if (!offerFirst(element)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public void addLast(E element) {
        if (!offerLast(element)) {
            throw new IllegalStateException("Deque is full");
        }
    }

    public void putFirst(E element) throws InterruptedException {
        checkElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkFirst(element);
        }
    }

    public void putLast(E element) throws InterruptedException {
        checkElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkLast(element);
        }
    }

    public E pollFirst() {
        synchronized (monitor) {
            return size == 0 ? null : unlinkFirst();
        }
    }

    public E pollLast() {
        synchronized (monitor) {
            return size == 0 ? null : unlinkLast();
        }
    }

    public E removeFirst() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkFirst();
        }
    }

    public E removeLast() {
        synchronized (monitor) {
            if (size == 0) {
                throw new IllegalStateException("Deque is empty");
            }
            return unlinkLast();
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return unlinkFirst();
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }
            return unlinkLast();
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.value;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.value;
        }
    }

    public E getFirst() {
        synchronized (monitor) {
            if (first == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return first.value;
        }
    }

    public E getLast() {
        synchronized (monitor) {
            if (last == null) {
                throw new IllegalStateException("Deque is empty");
            }
            return last.value;
        }
    }

    public void clear() {
        synchronized (monitor) {
            if (size != 0) {
                first = null;
                last = null;
                size = 0;
                monitor.notifyAll();
            }
        }
    }

    /** Adds at the tail. */
    public boolean add(E element) {
        addLast(element);
        return true;
    }

    /** Attempts to add at the tail. */
    public boolean offer(E element) {
        return offerLast(element);
    }

    /** Waits if necessary, then adds at the tail. */
    public void put(E element) throws InterruptedException {
        putLast(element);
    }

    /** Removes from the head, or returns null if empty. */
    public E poll() {
        return pollFirst();
    }

    /** Removes from the head, or throws if empty. */
    public E remove() {
        return removeFirst();
    }

    /** Waits if necessary, then removes from the head. */
    public E take() throws InterruptedException {
        return takeFirst();
    }

    /** Returns the head, or null if empty. */
    public E peek() {
        return peekFirst();
    }

    /** Returns the head, or throws if empty. */
    public E element() {
        return getFirst();
    }

    /** Adds at the head, or throws if full. */
    public void push(E element) {
        addFirst(element);
    }

    /** Removes from the head, or throws if empty. */
    public E pop() {
        return removeFirst();
    }
}
