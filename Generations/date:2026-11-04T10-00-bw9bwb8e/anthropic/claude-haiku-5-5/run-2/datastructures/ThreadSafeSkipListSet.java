@ThreadSafeSkipListSet.ThreadSafe
public final class ThreadSafeSkipListSet<T extends Comparable<T>> {
    @interface ThreadSafe {}


    private static final int MAX_LEVEL = 32;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int levels) {
            this.value = value;
            this.next = (Node<T>[]) new Node[levels];
        }
    }

    // Single monitor guarding all mutable state. Every access to the
    // structure (head, level, size, seed, node links) happens while holding it,
    // which provides mutual exclusion, visibility and safe publication of nodes.
    private final Object lock = new Object();

    private final int capacity;
    private final Node<T> head;
    private int level;   // highest level currently in use, guarded by lock
    private int size;    // number of elements, guarded by lock
    private long seed;   // PRNG state for level generation, guarded by lock

    /**
     * Creates an unbounded set.
     */
    public ThreadSafeSkipListSet() {
        this(Integer.MAX_VALUE);
    }

    /**
     * Creates a set with the given maximum number of elements.
     *
     * Blocking policy:
     *  - add(v) blocks (wait) while the set is full and v is not already present,
     *    and resumes once a remove frees a slot.
     *  - remove(v) and contains(v) never block; remove on an empty or absent
     *    element simply returns false.
     *  - tryAdd(v) never blocks and returns false if the set is full.
     */
    public ThreadSafeSkipListSet(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.head = new Node<T>(null, MAX_LEVEL);
        this.level = 1;
        this.size = 0;
        long s = 0x9E3779B97F4A7C15L ^ System.nanoTime();
        this.seed = (s == 0L) ? 0x2545F4914F6CDD1DL : s;
    }

    /**
     * Adds the value, blocking while the set is full (unless the value is already present).
     * Returns true if the value was inserted, false if it was already present.
     */
    public boolean add(T value) throws InterruptedException {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            while (true) {
                Node<T>[] preds = findPredecessors(value);
                Node<T> candidate = preds[0].next[0];
                if (candidate != null && candidate.value.compareTo(value) == 0) {
                    return false;
                }
                if (size < capacity) {
                    insertNode(value, preds);
                    lock.notifyAll();
                    return true;
                }
                lock.wait();
            }
        }
    }

    /**
     * Non-blocking add. Returns true if inserted, false if already present or full.
     */
    public boolean tryAdd(T value) {
        if (value == null) {
            throw new NullPointerException("null elements are not permitted");
        }
        synchronized (lock) {
            Node<T>[] preds = findPredecessors(value);
            Node<T> candidate = preds[0].next[0];
            if (candidate != null && candidate.value.compareTo(value) == 0) {
                return false;
            }
            if (size >= capacity) {
                return false;
            }
            insertNode(value, preds);
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Removes the value if present. Never blocks.
     * Returns true if the value was removed.
     */
    public boolean remove(T value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            if (size == 0) {
                return false;
            }
            Node<T>[] preds = findPredecessors(value);
            Node<T> target = preds[0].next[0];
            if (target == null || target.value.compareTo(value) != 0) {
                return false;
            }
            for (int i = 0; i < target.next.length; i++) {
                if (preds[i].next[i] == target) {
                    preds[i].next[i] = target.next[i];
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
     */
    public boolean contains(T value) {
        if (value == null) {
            return false;
        }
        synchronized (lock) {
            Node<T>[] preds = findPredecessors(value);
            Node<T> candidate = preds[0].next[0];
            return candidate != null && candidate.value.compareTo(value) == 0;
        }
    }

    /**
     * Returns the current number of elements.
     */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns true if the set currently has no elements.
     */
    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    /**
     * Returns the configured maximum number of elements.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Must be called while holding lock.
     * Returns, for each level, the rightmost node whose value is strictly less than value.
     */
    @SuppressWarnings("unchecked")
    private Node<T>[] findPredecessors(T value) {
        Node<T>[] preds = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T> x = head;
        for (int i = level - 1; i >= 0; i--) {
            Node<T> nxt = x.next[i];
            while (nxt != null && nxt.value.compareTo(value) < 0) {
                x = nxt;
                nxt = x.next[i];
            }
            preds[i] = x;
        }
        for (int i = level; i < MAX_LEVEL; i++) {
            preds[i] = head;
        }
        return preds;
    }

    /**
     * Must be called while holding lock and only when value is known to be absent
     * and size < capacity.
     */
    private void insertNode(T value, Node<T>[] preds) {
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                preds[i] = head;
            }
            level = newLevel;
        }
        Node<T> node = new Node<T>(value, newLevel);
        for (int i = 0; i < newLevel; i++) {
            node.next[i] = preds[i].next[i];
            preds[i].next[i] = node;
        }
        size++;
    }

    /**
     * Must be called while holding lock. Geometric distribution with p = 1/2.
     */
    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && (nextRandom() & 1L) == 1L) {
            lvl++;
        }
        return lvl;
    }

    /**
     * Must be called while holding lock. xorshift64 PRNG.
     */
    private long nextRandom() {
        long x = seed;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        seed = x;
        return x;
    }
}
