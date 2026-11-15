@ConcurrentLinkedList.ThreadSafe
public class ConcurrentLinkedList<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        private final T value;
        private Node<T> next;

        private Node(T value) {
            this.value = value;
        }
    }

    /*
     * Concurrency policy:
     * - All state (head, tail, size) is guarded by the monitor of 'lock'.
     * - Null elements are rejected, so null can signal "no element" from the try* methods.
     * - Bounded structure: addFirst/addLast block while the list is full.
     *   removeFirst blocks while the list is empty.
     * - Non-blocking variants (tryAddFirst, tryAddLast, tryRemoveFirst) return immediately.
     * - Every state change that frees or fills a slot calls notifyAll, and every wait
     *   is guarded by a while loop re-checking the condition.
     * - Blocking methods throw InterruptedException if interrupted while waiting;
     *   the list state is unchanged in that case.
     */
    private final Object lock = new Object();
    private final int capacity;
    private Node<T> head;
    private Node<T> tail;
    private int size;

    public ConcurrentLinkedList() {
        this(Integer.MAX_VALUE);
    }

    public ConcurrentLinkedList(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    /** Blocks while the list is full, then inserts at the front. */
    public void addFirst(T element) throws InterruptedException {
        checkNotNull(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            Node<T> node = new Node<>(element);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
            lock.notifyAll();
        }
    }

    /** Blocks while the list is full, then appends at the back. */
    public void addLast(T element) throws InterruptedException {
        checkNotNull(element);
        synchronized (lock) {
            while (size == capacity) {
                lock.wait();
            }
            Node<T> node = new Node<>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            lock.notifyAll();
        }
    }

    /** Non-blocking insert at the front. Returns false if the list is full. */
    public boolean tryAddFirst(T element) {
        checkNotNull(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            Node<T> node = new Node<>(element);
            node.next = head;
            head = node;
            if (tail == null) {
                tail = node;
            }
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /** Non-blocking append at the back. Returns false if the list is full. */
    public boolean tryAddLast(T element) {
        checkNotNull(element);
        synchronized (lock) {
            if (size == capacity) {
                return false;
            }
            Node<T> node = new Node<>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            lock.notifyAll();
            return true;
        }
    }

    /** Blocks while the list is empty, then removes and returns the first element. */
    public T removeFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            return removeHeadLocked();
        }
    }

    /** Non-blocking removal of the first element. Returns null if the list is empty. */
    public T tryRemoveFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeHeadLocked();
        }
    }

    /** Removes the first occurrence of the given element. Returns true if removed. */
    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            Node<T> prev = null;
            Node<T> cur = head;
            while (cur != null) {
                if (element.equals(cur.value)) {
                    if (prev == null) {
                        head = cur.next;
                    } else {
                        prev.next = cur.next;
                    }
                    if (cur == tail) {
                        tail = prev;
                    }
                    cur.next = null;
                    size--;
                    lock.notifyAll();
                    return true;
                }
                prev = cur;
                cur = cur.next;
            }
            return false;
        }
    }

    /** Returns the element at the given index. */
    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
            }
            Node<T> cur = head;
            for (int i = 0; i < index; i++) {
                cur = cur.next;
            }
            return cur.value;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            for (Node<T> cur = head; cur != null; cur = cur.next) {
                if (element.equals(cur.value)) {
                    return true;
                }
            }
            return false;
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

    public int capacity() {
        return capacity;
    }

    /** Removes all elements and wakes any blocked producers. */
    public void clear() {
        synchronized (lock) {
            head = null;
            tail = null;
            size = 0;
            lock.notifyAll();
        }
    }

    /** Returns a consistent snapshot of the current elements in order. */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int i = 0;
            for (Node<T> cur = head; cur != null; cur = cur.next) {
                result[i++] = cur.value;
            }
            return result;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Node<T> cur = head; cur != null; cur = cur.next) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(cur.value);
                first = false;
            }
            return sb.append(']').toString();
        }
    }

    /** Must be called while holding lock and with size > 0. */
    private T removeHeadLocked() {
        Node<T> node = head;
        head = node.next;
        if (head == null) {
            tail = null;
        }
        node.next = null;
        size--;
        lock.notifyAll();
        return node.value;
    }

    private static void checkNotNull(Object element) {
        if (element == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
