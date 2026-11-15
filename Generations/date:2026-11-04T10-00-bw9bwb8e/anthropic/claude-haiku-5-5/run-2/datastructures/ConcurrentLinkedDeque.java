@ConcurrentLinkedDeque.ThreadSafe
public class ConcurrentLinkedDeque<E> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        private E item;
        private Node<E> prev;
        private Node<E> next;

        private Node(E item) {
            this.item = item;
        }
    }

    /*
     * Blocking policy:
     *  - putFirst/putLast block while the deque is full (bounded mode).
     *  - takeFirst/takeLast block while the deque is empty.
     *  - offerFirst/offerLast never block; they return false if full.
     *  - pollFirst/pollLast never block; they return null if empty.
     *  - Blocking calls throw InterruptedException if the waiting thread is interrupted,
     *    and leave the deque unchanged.
     *  - Null elements are rejected with NullPointerException.
     *
     * Concurrency:
     *  - All state (head, tail, size) is accessed only while holding 'lock', which
     *    provides mutual exclusion, visibility, and safe publication of elements.
     *  - Every state change calls notifyAll so that waiting producers and consumers
     *    are woken and re-check their conditions in while loops (no lost wakeups,
     *    no spurious-wakeup errors).
     */

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
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    // ----- Blocking operations -----

    public void putFirst(E e) throws InterruptedException {
        checkNotNull(e);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            linkFirst(e);
            lock.notifyAll();
        }
    }

    public void putLast(E e) throws InterruptedException {
        checkNotNull(e);
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
            E result = unlinkFirst();
            lock.notifyAll();
            return result;
        }
    }

    public E takeLast() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E result = unlinkLast();
            lock.notifyAll();
            return result;
        }
    }

    // ----- Non-blocking operations -----

    public boolean offerFirst(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkFirst(e);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offerLast(E e) {
        checkNotNull(e);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            linkLast(e);
            lock.notifyAll();
            return true;
        }
    }

    public E pollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = unlinkFirst();
            lock.notifyAll();
            return result;
        }
    }

    public E pollLast() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E result = unlinkLast();
            lock.notifyAll();
            return result;
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

    // ----- Utility operations -----

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public int capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size == capacity;
        }
    }

    public void clear() {
        synchronized (lock) {
            Node<E> current = head;
            while (current != null) {
                Node<E> next = current.next;
                current.item = null;
                current.prev = null;
                current.next = null;
                current = next;
            }
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of the elements, from first to last. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            Node<E> current = head;
            int i = 0;
            while (current != null) {
                result[i++] = current.item;
                current = current.next;
            }
            return result;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            Node<E> current = head;
            boolean first = true;
            while (current != null) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(current.item);
                first = false;
                current = current.next;
            }
            return sb.append(']').toString();
        }
    }

    // ----- Internal helpers: caller must hold 'lock' -----

    private void linkFirst(E e) {
        Node<E> node = new Node<>(e);
        if (head == null) {
            head = node;
            tail = node;
        } else {
            node.next = head;
            head.prev = node;
            head = node;
        }
        size++;
    }

    private void linkLast(E e) {
        Node<E> node = new Node<>(e);
        if (tail == null) {
            head = node;
            tail = node;
        } else {
            node.prev = tail;
            tail.next = node;
            tail = node;
        }
        size++;
    }

    private E unlinkFirst() {
        Node<E> node = head;
        E item = node.item;
        Node<E> next = node.next;
        node.item = null;
        node.next = null;
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
        Node<E> node = tail;
        E item = node.item;
        Node<E> prev = node.prev;
        node.item = null;
        node.prev = null;
        tail = prev;
        if (prev == null) {
            head = null;
        } else {
            prev.next = null;
        }
        size--;
        return item;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
