import java.lang.*;

/**
 * A thread-safe doubly linked list.
 *
 * Policy:
 *  - The list is unbounded, so insertions never block.
 *  - Null elements are not permitted (null is used as the "nothing" result).
 *  - removeFirst/removeLast/pollFirst-style non-blocking operations return null when the list is empty.
 *  - takeFirst/takeLast block (using wait/notifyAll in a while loop) until an element is available.
 *  - pollFirst(timeout)/pollLast(timeout) block for at most the given time.
 *  - All state is guarded by a single private monitor, so every operation is atomic.
 */
@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<E> {
        E item;
        Node<E> prev;
        Node<E> next;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();

    // Guarded by lock
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public ConcurrentLinkedList() {
    }

    // ---------- internal helpers (must be called holding lock) ----------

    private void linkLast(T item) {
        Node<T> n = new Node<T>(item);
        if (tail == null) {
            head = n;
            tail = n;
        } else {
            n.prev = tail;
            tail.next = n;
            tail = n;
        }
        size++;
    }

    private void linkFirst(T item) {
        Node<T> n = new Node<T>(item);
        if (head == null) {
            head = n;
            tail = n;
        } else {
            n.next = head;
            head.prev = n;
            head = n;
        }
        size++;
    }

    private void linkBefore(T item, Node<T> succ) {
        Node<T> pred = succ.prev;
        Node<T> n = new Node<T>(item);
        n.prev = pred;
        n.next = succ;
        succ.prev = n;
        if (pred == null) {
            head = n;
        } else {
            pred.next = n;
        }
        size++;
    }

    private T unlink(Node<T> node) {
        T item = node.item;
        Node<T> p = node.prev;
        Node<T> n = node.next;
        if (p == null) {
            head = n;
        } else {
            p.next = n;
        }
        if (n == null) {
            tail = p;
        } else {
            n.prev = p;
        }
        node.prev = null;
        node.next = null;
        node.item = null;
        size--;
        return item;
    }

    private Node<T> nodeAt(int index) {
        if (index < (size >> 1)) {
            Node<T> x = head;
            for (int i = 0; i < index; i++) {
                x = x.next;
            }
            return x;
        } else {
            Node<T> x = tail;
            for (int i = size - 1; i > index; i--) {
                x = x.prev;
            }
            return x;
        }
    }

    private Node<T> findNode(Object o) {
        for (Node<T> x = head; x != null; x = x.next) {
            if (x.item.equals(o)) {
                return x;
            }
        }
        return null;
    }

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
    }

    // ---------- insertion ----------

    public void addFirst(T item) {
        checkNotNull(item);
        synchronized (lock) {
            linkFirst(item);
            lock.notifyAll();
        }
    }

    public void addLast(T item) {
        checkNotNull(item);
        synchronized (lock) {
            linkLast(item);
            lock.notifyAll();
        }
    }

    public boolean add(T item) {
        addLast(item);
        return true;
    }

    public void add(int index, T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (index < 0 || index > size) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
            }
            if (index == size) {
                linkLast(item);
            } else {
                linkBefore(item, nodeAt(index));
            }
            lock.notifyAll();
        }
    }

    // ---------- non-blocking removal ----------

    /** Removes and returns the first element, or null if empty. */
    public T removeFirst() {
        synchronized (lock) {
            if (head == null) {
                return null;
            }
            return unlink(head);
        }
    }

    /** Removes and returns the last element, or null if empty. */
    public T removeLast() {
        synchronized (lock) {
            if (tail == null) {
                return null;
            }
            return unlink(tail);
        }
    }

    /** Removes the element at index and returns it. */
    public T remove(int index) {
        synchronized (lock) {
            checkIndex(index);
            return unlink(nodeAt(index));
        }
    }

    /** Removes the first occurrence of o; returns true if removed. */
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            Node<T> n = findNode(o);
            if (n == null) {
                return false;
            }
            unlink(n);
            return true;
        }
    }

    // ---------- blocking removal ----------

    /** Removes and returns the first element, waiting while the list is empty. */
    public T takeFirst() throws InterruptedException {
        synchronized (lock) {
            while (head == null) {
                lock.wait();
            }
            return unlink(head);
        }
    }

    /** Removes and returns the last element, waiting while the list is empty. */
    public T takeLast() throws InterruptedException {
        synchronized (lock) {
            while (tail == null) {
                lock.wait();
            }
            return unlink(tail);
        }
    }

    /** Waits up to timeoutMillis for an element; returns null on timeout. */
    public T pollFirst(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        synchronized (lock) {
            while (head == null) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                long ms = remainingNanos / 1000000L;
                int ns = (int) (remainingNanos % 1000000L);
                if (ms == 0 && ns == 0) {
                    ns = 1;
                }
                lock.wait(ms, ns);
            }
            return unlink(head);
        }
    }

    /** Waits up to timeoutMillis for an element; returns null on timeout. */
    public T pollLast(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        synchronized (lock) {
            while (tail == null) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                long ms = remainingNanos / 1000000L;
                int ns = (int) (remainingNanos % 1000000L);
                if (ms == 0 && ns == 0) {
                    ns = 1;
                }
                lock.wait(ms, ns);
            }
            return unlink(tail);
        }
    }

    // ---------- queries ----------

    public T peekFirst() {
        synchronized (lock) {
            return head == null ? null : head.item;
        }
    }

    public T peekLast() {
        synchronized (lock) {
            return tail == null ? null : tail.item;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkIndex(index);
            return nodeAt(index).item;
        }
    }

    public T set(int index, T item) {
        checkNotNull(item);
        synchronized (lock) {
            checkIndex(index);
            Node<T> n = nodeAt(index);
            T old = n.item;
            n.item = item;
            return old;
        }
    }

    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        synchronized (lock) {
            return findNode(o) != null;
        }
    }

    public int indexOf(Object o) {
        if (o == null) {
            return -1;
        }
        synchronized (lock) {
            int i = 0;
            for (Node<T> x = head; x != null; x = x.next, i++) {
                if (x.item.equals(o)) {
                    return i;
                }
            }
            return -1;
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

    public void clear() {
        synchronized (lock) {
            Node<T> x = head;
            while (x != null) {
                Node<T> next = x.next;
                x.item = null;
                x.prev = null;
                x.next = null;
                x = next;
            }
            head = null;
            tail = null;
            size = 0;
        }
    }

    /** Returns a consistent snapshot of the list contents. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int i = 0;
            for (Node<T> x = head; x != null; x = x.next) {
                result[i++] = x.item;
            }
            return result;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (Node<T> x = head; x != null; x = x.next) {
                if (x != head) {
                    sb.append(", ");
                }
                sb.append(x.item);
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
