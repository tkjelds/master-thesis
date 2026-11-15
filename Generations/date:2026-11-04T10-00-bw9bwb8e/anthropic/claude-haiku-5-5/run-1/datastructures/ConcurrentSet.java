/**
 * A thread-safe, bounded (optionally) set implemented with a hand-rolled
 * chained hash table. All shared state is guarded by a single private lock
 * object using synchronized blocks, and all waiting is done with
 * wait/notifyAll inside while loops.
 *
 * Blocking policy:
 *  - add(value) blocks while the set is full (size == capacity) and the value
 *    is not already present. It returns false if the value is already present,
 *    true once inserted. If the waiting thread is interrupted, InterruptedException
 *    is thrown and the set is left unchanged.
 *  - tryAdd(value) never blocks. It returns false if the value is present or the
 *    set is full.
 *  - remove(value), contains(value), size(), isEmpty(), clear() and toArray()
 *    never block. Removals (and clear) wake threads blocked in add().
 *  - null elements are rejected with NullPointerException.
 */
@ConcurrentSet.ThreadSafe
public final class ConcurrentSet<T> {
    @interface ThreadSafe {}


    private static final int INITIAL_BUCKETS = 16;

    private static final class Node<T> {
        final T value;
        Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    private final Object lock = new Object();
    private final int capacity;

    // Guarded by lock.
    private Node<T>[] buckets;
    private int size;

    /** Creates an unbounded set. */
    public ConcurrentSet() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a set holding at most {@code capacity} elements.
     *
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public ConcurrentSet(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.buckets = newBuckets(INITIAL_BUCKETS);
        this.size = 0;
    }

    /**
     * Adds the value, blocking while the set is full and the value is absent.
     *
     * @return true if the value was inserted, false if it was already present
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean add(T value) throws InterruptedException {
        checkNotNull(value);
        synchronized (lock) {
            while (true) {
                if (findNode(value) != null) {
                    return false;
                }
                if (size < capacity) {
                    insertUnchecked(value);
                    lock.notifyAll();
                    return true;
                }
                lock.wait();
            }
        }
    }

    /**
     * Non-blocking add.
     *
     * @return true if inserted; false if already present or the set is full
     */
    public boolean tryAdd(T value) {
        checkNotNull(value);
        synchronized (lock) {
            if (findNode(value) != null || size >= capacity) {
                return false;
            }
            insertUnchecked(value);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes the value if present.
     *
     * @return true if the value was removed, false if it was absent
     */
    public boolean remove(T value) {
        checkNotNull(value);
        synchronized (lock) {
            int index = indexFor(value, buckets.length);
            Node<T> prev = null;
            Node<T> node = buckets[index];
            while (node != null) {
                if (node.value.equals(value)) {
                    if (prev == null) {
                        buckets[index] = node.next;
                    } else {
                        prev.next = node.next;
                    }
                    size--;
                    lock.notifyAll();
                    return true;
                }
                prev = node;
                node = node.next;
            }
            return false;
        }
    }

    /** @return true if the value is currently in the set */
    public boolean contains(T value) {
        checkNotNull(value);
        synchronized (lock) {
            return findNode(value) != null;
        }
    }

    /** @return the number of elements currently in the set */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** @return true if the set currently holds no elements */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Removes all elements and wakes any threads blocked in add(). */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = null;
            }
            size = 0;
            lock.notifyAll();
        }
    }

    /** @return a consistent snapshot of the current elements */
    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int k = 0;
            for (int i = 0; i < buckets.length; i++) {
                for (Node<T> n = buckets[i]; n != null; n = n.next) {
                    result[k++] = n.value;
                }
            }
            return result;
        }
    }

    // ----- private helpers; all must be called while holding lock -----

    private Node<T> findNode(T value) {
        int index = indexFor(value, buckets.length);
        for (Node<T> n = buckets[index]; n != null; n = n.next) {
            if (n.value.equals(value)) {
                return n;
            }
        }
        return null;
    }

    private void insertUnchecked(T value) {
        if (size + 1 > (buckets.length * 3) / 4) {
            rehash(buckets.length * 2);
        }
        int index = indexFor(value, buckets.length);
        buckets[index] = new Node<T>(value, buckets[index]);
        size++;
    }

    private void rehash(int newLength) {
        Node<T>[] newBuckets = newBuckets(newLength);
        for (int i = 0; i < buckets.length; i++) {
            Node<T> n = buckets[i];
            while (n != null) {
                Node<T> next = n.next;
                int idx = indexFor(n.value, newLength);
                n.next = newBuckets[idx];
                newBuckets[idx] = n;
                n = next;
            }
        }
        buckets = newBuckets;
    }

    @SuppressWarnings("unchecked")
    private static <T> Node<T>[] newBuckets(int length) {
        return (Node<T>[]) new Node[length];
    }

    private static int indexFor(Object value, int length) {
        int h = value.hashCode();
        h ^= (h >>> 16);
        return (h & 0x7fffffff) % length;
    }

    private static void checkNotNull(Object value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
    }
}
