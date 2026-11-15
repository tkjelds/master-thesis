@SkipListSet.ThreadSafe
public class SkipListSet<T extends Comparable<? super T>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;
    private static final long INITIAL_SEED = 0x9E3779B97F4A7C15L;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int levels) {
            this.value = value;
            this.next = (Node<T>[]) new Node[levels];
        }
    }

    // Single monitor guarding all mutable state. Also used for wait/notifyAll.
    private final Object lock = new Object();

    // Sentinel head; its value is never compared.
    private final Node<T> head;
    private final int capacity; // 0 means unbounded

    // All fields below are accessed only while holding 'lock'.
    private int level;   // current number of levels in use (>= 1)
    private int size;
    private long seed;   // xorshift PRNG state for level generation

    /**
     * Creates an unbounded set. add() never blocks on capacity;
     * pollFirst() blocks while the set is empty.
     */
    public SkipListSet() {
        this(0);
    }

    /**
     * Creates a set with the given capacity (must be >= 1).
     * Policy for a full set: add() blocks until space becomes available
     * (or the calling thread is interrupted).
     * Policy for an empty set: pollFirst() blocks until an element is available;
     * tryPollFirst() returns null immediately.
     * Policy for duplicate add / missing remove: returns false immediately.
     */
    public SkipListSet(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be >= 0");
        }
        this.capacity = capacity;
        this.head = new Node<T>(null, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
        this.seed = INITIAL_SEED;
    }

    /**
     * Adds the value. Returns true if added, false if already present.
     * Blocks while the set is bounded and full (and the value is not present).
     */
    public boolean add(T value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (true) {
                Node<T>[] update = newUpdateArray();
                if (findPredecessors(value, update) != null) {
                    return false;
                }
                if (capacity > 0 && size >= capacity) {
                    lock.wait(); // guarded by while loop; re-checks presence after wake
                    continue;
                }
                insertNode(value, update);
                lock.notifyAll();
                return true;
            }
        }
    }

    /**
     * Removes the value. Returns true if it was present and removed.
     */
    public boolean remove(T value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            Node<T>[] update = newUpdateArray();
            Node<T> target = findPredecessors(value, update);
            if (target == null) {
                return false;
            }
            unlinkNode(target, update);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Returns true if the value is present.
     */
    public boolean contains(T value) {
        if (value == null) {
            return false;
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
            Node<T> cand = x.next[0];
            return cand != null && cand.value.compareTo(value) == 0;
        }
    }

    /**
     * Removes and returns the smallest element, blocking while the set is empty.
     */
    public T pollFirst() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait(); // guarded by while loop
            }
            return removeFirstLocked();
        }
    }

    /**
     * Removes and returns the smallest element, or null if the set is empty.
     * Never blocks.
     */
    public T tryPollFirst() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            return removeFirstLocked();
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

    /** Returns the configured capacity, or 0 if unbounded. */
    public int capacity() {
        return capacity;
    }

    // ----- Private helpers: all must be called while holding 'lock'. -----

    @SuppressWarnings("unchecked")
    private Node<T>[] newUpdateArray() {
        Node<T>[] update = (Node<T>[]) new Node[MAX_LEVEL];
        return update;
    }

    /**
     * Fills update[i] with the rightmost node at level i whose value is < value.
     * Returns the node holding an equal value, or null if absent.
     * Entries update[i] for i >= level are not touched; callers handle them.
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
        Node<T> cand = x.next[0];
        if (cand != null && cand.value.compareTo(value) == 0) {
            return cand;
        }
        return null;
    }

    private void insertNode(T value, Node<T>[] update) {
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                update[i] = head;
            }
            level = newLevel;
        }
        Node<T> node = new Node<T>(value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
        size++;
    }

    private void unlinkNode(Node<T> target, Node<T>[] update) {
        int levels = target.next.length;
        for (int i = 0; i < levels; i++) {
            if (update[i].next[i] == target) {
                update[i].next[i] = target.next[i];
            }
        }
        shrinkLevel();
        size--;
    }

    /** Precondition: size > 0. The first node is the smallest element. */
    private T removeFirstLocked() {
        Node<T> first = head.next[0];
        int levels = first.next.length;
        for (int i = 0; i < levels; i++) {
            // first is the smallest, so it is directly after head at every level it occupies
            head.next[i] = first.next[i];
        }
        shrinkLevel();
        size--;
        lock.notifyAll();
        return first.value;
    }

    private void shrinkLevel() {
        while (level > 1 && head.next[level - 1] == null) {
            level--;
        }
    }

    /** Geometric distribution with p = 1/2, capped at MAX_LEVEL. */
    private int randomLevel() {
        long r = nextRandom();
        int lvl = 1;
        while ((r & 1L) != 0L && lvl < MAX_LEVEL) {
            lvl++;
            r >>>= 1;
        }
        return lvl;
    }

    /** xorshift64; state is only touched under 'lock'. */
    private long nextRandom() {
        long x = seed;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        seed = x;
        return x;
    }
}
