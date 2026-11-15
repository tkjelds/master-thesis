/**
 * A thread-safe, set-semantics skip list.
 *
 * Concurrency design:
 *  - A single private monitor ({@code lock}) guards every mutable field (head links,
 *    level, size, and the PRNG state). All accesses happen inside synchronized blocks
 *    on that monitor, which gives mutual exclusion, visibility, and safe publication
 *    of nodes (a node is fully initialized before it is linked while the lock is held).
 *  - The head sentinel and capacity are final, so they are safely published on construction.
 *
 * Blocking policy:
 *  - Duplicate detection takes precedence: {@code add} of a value already present returns
 *    {@code false} immediately, even when the set is full.
 *  - If the set is full (size == capacity) and the value is absent, {@code add} blocks
 *    using wait() until a removal frees a slot. The wait is guarded by a while loop that
 *    re-checks both duplication and capacity after every wakeup.
 *  - {@code remove} never blocks: it returns {@code false} if the value is absent.
 *  - {@code contains}, {@code size}, and {@code isEmpty} never block.
 *  - Waiting threads that are interrupted receive an InterruptedException.
 */
@ConcurrentSkipListSet.ThreadSafe
public final class ConcurrentSkipListSet<T extends Comparable<? super T>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<E> {
        final E value;
        final Node<E>[] next;

        @SuppressWarnings("unchecked")
        Node(E value, int level) {
            this.value = value;
            this.next = (Node<E>[]) new Node[level];
        }
    }

    private final Object lock = new Object();
    private final Node<T> head;
    private final int capacity;
    private int size;
    private int level;
    private long rng;

    /** Creates an effectively unbounded set. */
    public ConcurrentSkipListSet() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a set that holds at most {@code capacity} elements.
     *
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public ConcurrentSkipListSet(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.head = new Node<>(null, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
        this.rng = 0x9E3779B97F4A7C15L;
    }

    /**
     * Adds the value if it is not already present.
     *
     * @return true if the value was added; false if it was already present
     * @throws NullPointerException if value is null
     * @throws InterruptedException if interrupted while waiting for free capacity
     */
    public boolean add(T value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            Node<T>[] update;
            while (true) {
                update = newUpdateArray();
                if (findPredecessors(value, update) != null) {
                    return false;
                }
                if (size < capacity) {
                    break;
                }
                lock.wait();
            }

            int newLevel = randomLevel();
            if (newLevel > level) {
                for (int i = level; i < newLevel; i++) {
                    update[i] = head;
                }
                level = newLevel;
            }

            Node<T> node = new Node<>(value, newLevel);
            for (int i = 0; i < newLevel; i++) {
                node.next[i] = update[i].next[i];
                update[i].next[i] = node;
            }
            size++;
            return true;
        }
    }

    /**
     * Removes the value if present. Never blocks.
     *
     * @return true if the value was removed; false if it was not present
     * @throws NullPointerException if value is null
     */
    public boolean remove(T value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            Node<T>[] update = newUpdateArray();
            Node<T> target = findPredecessors(value, update);
            if (target == null) {
                return false;
            }
            for (int i = 0; i < target.next.length; i++) {
                if (update[i].next[i] == target) {
                    update[i].next[i] = target.next[i];
                }
            }
            while (level > 1 && head.next[level - 1] == null) {
                level--;
            }
            size--;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Returns true if the value is present. Never blocks.
     *
     * @throws NullPointerException if value is null
     */
    public boolean contains(T value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            Node<T> x = head;
            for (int i = level - 1; i >= 0; i--) {
                Node<T> nxt = x.next[i];
                while (nxt != null && nxt.value.compareTo(value) < 0) {
                    x = nxt;
                    nxt = x.next[i];
                }
            }
            Node<T> candidate = x.next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    /** Returns the current number of elements. Never blocks. */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Returns true if the set currently has no elements. Never blocks. */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /** Returns the maximum number of elements this set may hold. */
    public int capacity() {
        return capacity;
    }

    /**
     * Fills {@code update[i]} with the rightmost node at level i whose value is less than
     * {@code value}. Returns the node holding {@code value} if present, otherwise null.
     * Must be called while holding {@code lock}.
     */
    private Node<T> findPredecessors(T value, Node<T>[] update) {
        Node<T> x = head;
        for (int i = level - 1; i >= 0; i--) {
            Node<T> nxt = x.next[i];
            while (nxt != null && nxt.value.compareTo(value) < 0) {
                x = nxt;
                nxt = x.next[i];
            }
            update[i] = x;
        }
        Node<T> candidate = x.next[0];
        if (candidate != null && candidate.value.compareTo(value) == 0) {
            return candidate;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Node<T>[] newUpdateArray() {
        Node<T>[] update = (Node<T>[]) new Node[MAX_LEVEL];
        for (int i = 0; i < MAX_LEVEL; i++) {
            update[i] = head;
        }
        return update;
    }

    /** xorshift64 generator; must be called while holding {@code lock}. */
    private int randomLevel() {
        long x = rng;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        rng = x;
        int lvl = 1;
        long bits = x;
        while (lvl < MAX_LEVEL && (bits & 1L) == 1L) {
            lvl++;
            bits >>>= 1;
        }
        return lvl;
    }
}
