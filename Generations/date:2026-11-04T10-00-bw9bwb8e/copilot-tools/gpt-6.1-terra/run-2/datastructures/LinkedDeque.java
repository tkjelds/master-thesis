/**
 * A thread-safe, bounded linked deque.
 *
 * Null elements are not permitted. The default capacity is Integer.MAX_VALUE.
 * Offer and poll operations return immediately; put and take operations block
 * until space or an element is available, respectively. Blocking operations
 * propagate interruption without changing the deque.
 */
@LinkedDeque.ThreadSafe
public final class LinkedDeque<E> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E item;
        Node<E> previous;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object monitor = new Object();
    private final int capacity;
    private Node<E> first;
    private Node<E> last;
    private int size;

    public LinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public LinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
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
        requireElement(element);
        synchronized (monitor) {
            if (size == capacity) {
                return false;
            }
            linkFirst(element);
            return true;
        }
    }

    public boolean offerLast(E element) {
        requireElement(element);
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
        requireElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkFirst(element);
        }
    }

    public void putLast(E element) throws InterruptedException {
        requireElement(element);
        synchronized (monitor) {
            while (size == capacity) {
                monitor.wait();
            }
            linkLast(element);
        }
    }

    public E peekFirst() {
        synchronized (monitor) {
            return first == null ? null : first.item;
        }
    }

    public E peekLast() {
        synchronized (monitor) {
            return last == null ? null : last.item;
        }
    }

    public E getFirst() {
        E element = peekFirst();
        if (element == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return element;
    }

    public E getLast() {
        E element = peekLast();
        if (element == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return element;
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
        E element = pollFirst();
        if (element == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return element;
    }

    public E removeLast() {
        E element = pollLast();
        if (element == null) {
            throw new IllegalStateException("Deque is empty");
        }
        return element;
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

    private static void requireElement(Object element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
    }

    // The following helpers are called only while holding monitor.
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
        E element = node.item;
        first = node.next;
        if (first == null) {
            last = null;
        } else {
            first.previous = null;
        }
        node.item = null;
        node.next = null;
        size--;
        monitor.notifyAll();
        return element;
    }

    private E unlinkLast() {
        Node<E> node = last;
        E element = node.item;
        last = node.previous;
        if (last == null) {
            first = null;
        } else {
            last.next = null;
        }
        node.item = null;
        node.previous = null;
        size--;
        monitor.notifyAll();
        return element;
    }
}
