@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(E item, Node<E> prev, Node<E> next) {
            this.item = item;
            this.prev = prev;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private int size;
    private Node<E> head;
    private Node<E> tail;

    public ConcurrentLinkedDeque() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedDeque(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        this.size = 0;
        this.head = null;
        this.tail = null;
    }

    public void putFirst(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            linkFirst(element);
            lock.notifyAll();
        }
    }

    public void putLast(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            while (size >= capacity) {
                lock.wait();
            }
            linkLast(element);
            lock.notifyAll();
        }
    }

    public boolean offerFirst(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            linkFirst(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }
        synchronized (lock) {
            if (size >= capacity) {
                return false;
            }
            linkLast(element);
            lock.notifyAll();
            return true;
        }
    }

    public E takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = unlinkFirst();
            lock.notifyAll();
            return item;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = unlinkLast();
            lock.notifyAll();
            return item;
        }
    }

    public E peekFirst() {
        synchronized (lock) {
            return (head != null) ? head.item : null;
        }
    }

    public E peekLast() {
        synchronized (lock) {
            return (tail != null) ? tail.item : null;
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

    public boolean isFull() {
        synchronized (lock) {
            return size >= capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return capacity - size;
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<E> curr = head;
            while (curr != null) {
                Node<E> next = curr.next;
                curr.item = null;
                curr.prev = null;
                curr.next = null;
                curr = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    private void linkFirst(E element) {
        Node<E> oldHead = head;
        Node<E> newNode = new Node<E>(element, null, oldHead);
        head = newNode;
        if (oldHead == null) {
            tail = newNode;
        } else {
            oldHead.prev = newNode;
        }
        size++;
    }

    private void linkLast(E element) {
        Node<E> oldTail = tail;
        Node<E> newNode = new Node<E>(element, oldTail, null);
        tail = newNode;
        if (oldTail == null) {
            head = newNode;
        } else {
            oldTail.next = newNode;
        }
        size++;
    }

    private E unlinkFirst() {
        Node<E> first = head;
        E item = first.item;
        Node<E> next = first.next;
        first.item = null;
        first.next = null;
        head = next;
        if (next == null) {
            tail = null;
        } else {
            next.prev = null;
        }
        size--;
        return item;
    }

    private E unlinkLast() {
        Node<E> last = tail;
        E item = last.item;
        Node<E> prev = last.prev;
        last.item = null;
        last.prev = null;
        tail = prev;
        if (prev == null) {
            head = null;
        } else {
            prev.next = null;
        }
        size--;
        return item;
    }
}
