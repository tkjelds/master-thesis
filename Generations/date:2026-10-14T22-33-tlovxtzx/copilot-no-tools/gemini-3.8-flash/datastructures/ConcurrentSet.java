@ConcurrentSet.ThreadSafe
public class ConcurrentSet<E> {
    @interface ThreadSafe {}


    /*
     * Thread-Safe Concurrent Bounded Set Implementation
     *
     * Synchronization and Concurrency Policy:
     * - Thread-safety is achieved using intrinsic object monitors via synchronized blocks.
     * - No method declaration uses the 'synchronized' modifier; synchronized blocks guard
     *   all critical sections on an explicit, private final monitor object ('lock').
     * - State changes and visibility are governed by monitor entry/exit happens-before edges,
     *   preventing lost updates, stale reads, and inconsistent states.
     *
     * Policy for Operations That Cannot Complete Immediately:
     * 1. Bounded Capacity (Full Structure):
     *    - Blocking: put(E element) blocks using wait() inside a while loop until capacity
     *      becomes available (unless the element is already present, which completes immediately).
     *    - Timed Blocking: offer(E element, long timeoutMillis) waits up to the specified
     *      timeout for capacity to become available.
     *    - Non-Blocking: add(E element) and offer(E element) immediately return false if
     *      the set is at capacity or if the element is already present.
     *
     * 2. Empty Structure:
     *    - Blocking: take() blocks using wait() inside a while loop until an element is present,
     *      then removes and returns that element.
     *    - Timed Blocking: poll(long timeoutMillis) waits up to the specified timeout for an
     *      element to become available.
     *    - Non-Blocking: poll() immediately returns null if the set is empty.
     *
     * Notification Policy:
     * - Every successful insertion calls lock.notifyAll() to wake threads waiting for elements
     *   in an empty set.
     * - Every successful removal or clear calls lock.notifyAll() to wake threads waiting for
     *   available capacity in a full set.
     * - notifyAll() is used exclusively over notify() to avoid missed signals across multiple
     *   condition predicates sharing the intrinsic monitor.
     */

    private static final int DEFAULT_INITIAL_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int MAXIMUM_TABLE_CAPACITY = 1 << 30;

    private final Object lock = new Object();
    private final int capacity;

    private Node<E>[] table;
    private Node<E> head;
    private Node<E> tail;
    private int size;

    private static final class Node<E> {
        final E item;
        final int hash;
        Node<E> next;
        Node<E> prevOrder;
        Node<E> nextOrder;

        Node(E item, int hash, Node<E> next) {
            this.item = item;
            this.hash = hash;
            this.next = next;
        }
    }

    public ConcurrentSet() {
        this(Integer.MAX_VALUE);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
        int initCap = DEFAULT_INITIAL_CAPACITY;
        if (capacity < initCap) {
            initCap = 1;
            while (initCap < capacity) {
                initCap <<= 1;
            }
        }
        this.table = (Node<E>[]) new Node[initCap];
        this.head = null;
        this.tail = null;
        this.size = 0;
    }

    public int capacity() {
        return this.capacity;
    }

    public int size() {
        synchronized (lock) {
            return this.size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return this.size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return this.size >= this.capacity;
        }
    }

    public int remainingCapacity() {
        synchronized (lock) {
            return this.capacity - this.size;
        }
    }

    public boolean contains(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            return containsInternal(element);
        }
    }

    public boolean add(E element) {
        return offer(element);
    }

    public boolean offer(E element) {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (lock) {
            if (size >= capacity || containsInternal(element)) {
                return false;
            }
            insertInternal(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean put(E element) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        synchronized (lock) {
            while (size >= capacity && !containsInternal(element)) {
                lock.wait();
            }
            if (containsInternal(element)) {
                return false;
            }
            insertInternal(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean offer(E element, long timeoutMillis) throws InterruptedException {
        if (element == null) {
            throw new NullPointerException("Null elements are not permitted");
        }
        if (timeoutMillis <= 0) {
            return offer(element);
        }
        synchronized (lock) {
            long remainingNanos = timeoutMillis * 1000000L;
            long deadlineNanos = System.nanoTime() + remainingNanos;
            while (size >= capacity && !containsInternal(element)) {
                if (remainingNanos <= 0) {
                    return false;
                }
                long millis = remainingNanos / 1000000L;
                int nanos = (int) (remainingNanos % 1000000L);
                if (millis == 0 && nanos > 0) {
                    millis = 1;
                }
                lock.wait(millis);
                remainingNanos = deadlineNanos - System.nanoTime();
            }
            if (containsInternal(element)) {
                return false;
            }
            insertInternal(element);
            lock.notifyAll();
            return true;
        }
    }

    public boolean remove(Object element) {
        if (element == null) {
            return false;
        }
        synchronized (lock) {
            boolean removed = removeInternal(element);
            if (removed) {
                lock.notifyAll();
            }
            return removed;
        }
    }

    public E take() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            E item = removeHeadInternal();
            lock.notifyAll();
            return item;
        }
    }

    public E poll() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            E item = removeHeadInternal();
            lock.notifyAll();
            return item;
        }
    }

    public E poll(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) {
            return poll();
        }
        synchronized (lock) {
            long remainingNanos = timeoutMillis * 1000000L;
            long deadlineNanos = System.nanoTime() + remainingNanos;
            while (size == 0) {
                if (remainingNanos <= 0) {
                    return null;
                }
                long millis = remainingNanos / 1000000L;
                int nanos = (int) (remainingNanos % 1000000L);
                if (millis == 0 && nanos > 0) {
                    millis = 1;
                }
                lock.wait(millis);
                remainingNanos = deadlineNanos - System.nanoTime();
            }
            E item = removeHeadInternal();
            lock.notifyAll();
            return item;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (size > 0) {
                for (int i = 0; i < table.length; i++) {
                    table[i] = null;
                }
                Node<E> curr = head;
                while (curr != null) {
                    Node<E> next = curr.nextOrder;
                    curr.prevOrder = null;
                    curr.nextOrder = null;
                    curr.next = null;
                    curr = next;
                }
                head = null;
                tail = null;
                size = 0;
                lock.notifyAll();
            }
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] array = new Object[size];
            int i = 0;
            Node<E> curr = head;
            while (curr != null) {
                array[i++] = curr.item;
                curr = curr.nextOrder;
            }
            return array;
        }
    }

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private static int indexFor(int hash, int tableLength) {
        return hash & (tableLength - 1);
    }

    private boolean containsInternal(Object element) {
        if (size == 0) {
            return false;
        }
        int h = hash(element);
        int idx = indexFor(h, table.length);
        Node<E> curr = table[idx];
        while (curr != null) {
            if (curr.hash == h && (curr.item == element || element.equals(curr.item))) {
                return true;
            }
            curr = curr.next;
        }
        return false;
    }

    private void insertInternal(E element) {
        int h = hash(element);
        int idx = indexFor(h, table.length);
        Node<E> newNode = new Node<E>(element, h, table[idx]);
        table[idx] = newNode;

        if (tail == null) {
            head = newNode;
            tail = newNode;
        } else {
            tail.nextOrder = newNode;
            newNode.prevOrder = tail;
            tail = newNode;
        }

        size++;
        if (size > table.length * LOAD_FACTOR) {
            resize();
        }
    }

    private boolean removeInternal(Object element) {
        if (size == 0) {
            return false;
        }
        int h = hash(element);
        int idx = indexFor(h, table.length);
        Node<E> prev = null;
        Node<E> curr = table[idx];
        while (curr != null) {
            if (curr.hash == h && (curr.item == element || element.equals(curr.item))) {
                if (prev == null) {
                    table[idx] = curr.next;
                } else {
                    prev.next = curr.next;
                }

                if (curr.prevOrder != null) {
                    curr.prevOrder.nextOrder = curr.nextOrder;
                } else {
                    head = curr.nextOrder;
                }
                if (curr.nextOrder != null) {
                    curr.nextOrder.prevOrder = curr.prevOrder;
                } else {
                    tail = curr.prevOrder;
                }

                curr.next = null;
                curr.prevOrder = null;
                curr.nextOrder = null;
                size--;
                return true;
            }
            prev = curr;
            curr = curr.next;
        }
        return false;
    }

    private E removeHeadInternal() {
        if (head == null) {
            return null;
        }
        Node<E> node = head;
        E item = node.item;

        int idx = indexFor(node.hash, table.length);
        Node<E> prev = null;
        Node<E> curr = table[idx];
        while (curr != null) {
            if (curr == node) {
                if (prev == null) {
                    table[idx] = curr.next;
                } else {
                    prev.next = curr.next;
                }
                break;
            }
            prev = curr;
            curr = curr.next;
        }

        head = node.nextOrder;
        if (head != null) {
            head.prevOrder = null;
        } else {
            tail = null;
        }

        node.next = null;
        node.prevOrder = null;
        node.nextOrder = null;
        size--;
        return item;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        int oldCap = table.length;
        if (oldCap >= MAXIMUM_TABLE_CAPACITY) {
            return;
        }
        int newCap = oldCap << 1;
        Node<E>[] newTable = (Node<E>[]) new Node[newCap];

        Node<E> curr = head;
        while (curr != null) {
            int idx = indexFor(curr.hash, newCap);
            curr.next = newTable[idx];
            newTable[idx] = curr;
            curr = curr.nextOrder;
        }
        table = newTable;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof ConcurrentSet)) {
            return false;
        }
        ConcurrentSet<?> other = (ConcurrentSet<?>) o;
        Object lock1 = this.lock;
        Object lock2 = other.lock;
        int h1 = System.identityHashCode(lock1);
        int h2 = System.identityHashCode(lock2);

        if (h1 < h2) {
            synchronized (lock1) {
                synchronized (lock2) {
                    return equalsInternal(other);
                }
            }
        } else if (h1 > h2) {
            synchronized (lock2) {
                synchronized (lock1) {
                    return equalsInternal(other);
                }
            }
        } else {
            synchronized (ConcurrentSet.class) {
                synchronized (lock1) {
                    synchronized (lock2) {
                        return equalsInternal(other);
                    }
                }
            }
        }
    }

    private boolean equalsInternal(ConcurrentSet<?> other) {
        if (this.size != other.size) {
            return false;
        }
        Node<E> curr = this.head;
        while (curr != null) {
            if (!other.containsInternal(curr.item)) {
                return false;
            }
            curr = curr.nextOrder;
        }
        return true;
    }

    @Override
    public int hashCode() {
        synchronized (lock) {
            int h = 0;
            Node<E> curr = head;
            while (curr != null) {
                if (curr.item != null) {
                    h += curr.item.hashCode();
                }
                curr = curr.nextOrder;
            }
            return h;
        }
    }

    @Override
    public String toString() {
        synchronized (lock) {
            if (size == 0) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Node<E> curr = head;
            while (curr != null) {
                sb.append(curr.item == this ? "(this Set)" : curr.item);
                curr = curr.nextOrder;
                if (curr != null) {
                    sb.append(',').append(' ');
                }
            }
            sb.append(']');
            return sb.toString();
        }
    }
}
