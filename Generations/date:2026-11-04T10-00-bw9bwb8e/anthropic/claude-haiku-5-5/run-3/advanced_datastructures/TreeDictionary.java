@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    /** Deterministic total order over keys. Must not mutate or reenter the collection. */
    public interface Order<K> {
        int compare(K left, K right);
    }

    /** Immutable key/value pair returned by entryAt. */
    public static final class Entry<K, V> {
        private final K key;
        private final V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K key() {
            return key;
        }

        public V value() {
            return value;
        }

        @Override
        public String toString() {
            return "Entry[" + key + "=" + value + "]";
        }
    }

    /**
     * Immutable view of one historical state. Captured in O(1) by sharing the
     * persistent root node. Later updates never alter it.
     */
    public static final class Snapshot<K, V> {
        private final Order<K> order;
        private final Node<K, V> root;

        private Snapshot(Order<K> order, Node<K, V> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return sizeOf(root);
        }

        public V get(K key) {
            checkNotNull(key, "key");
            Node<K, V> n = find(order, root, key);
            return n == null ? null : n.value;
        }

        public Entry<K, V> entryAt(int index) {
            checkIndex(index, sizeOf(root));
            return selectEntry(root, index);
        }
    }

    /*
     * Concurrency policy: all operations are non-blocking. Each public operation
     * runs to completion while holding the single collection monitor, so every
     * operation is linearizable at its critical section. Because the collection is
     * unbounded, no operation waits for capacity. Invalid indices throw
     * IndexOutOfBoundsException, and null arguments throw NullPointerException,
     * both before any mutation.
     *
     * The tree is persistent: nodes are immutable and updates copy only the path
     * from the root to the affected node. Therefore the root reference guarded by
     * the lock is the only mutable state, and snapshots share nodes safely.
     */

    /** Immutable red-black tree node with subtree size. */
    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int size;

        Node(boolean red, Node<K, V> left, K key, V value, Node<K, V> right) {
            this.red = red;
            this.left = left;
            this.key = key;
            this.value = value;
            this.right = right;
            this.size = sizeOf(left) + sizeOf(right) + 1;
        }
    }

    /** Result of a deletion step: the new subtree and whether its black height dropped by one. */
    private static final class Removal<K, V> {
        final Node<K, V> node;
        final boolean shortened;

        Removal(Node<K, V> node, boolean shortened) {
            this.node = node;
            this.shortened = shortened;
        }
    }

    private final Order<K> order;
    private final Object lock = new Object();
    private Node<K, V> root; // guarded by lock

    public TreeDictionary(Order<K> order) {
        this.order = checkNotNull(order, "order");
    }

    public V put(K key, V value) {
        checkNotNull(key, "key");
        checkNotNull(value, "value");
        synchronized (lock) {
            Node<K, V> existing = find(order, root, key);
            Node<K, V> newRoot = insert(root, key, value);
            root = blacken(newRoot);
            return existing == null ? null : existing.value;
        }
    }

    public V get(K key) {
        checkNotNull(key, "key");
        synchronized (lock) {
            Node<K, V> n = find(order, root, key);
            return n == null ? null : n.value;
        }
    }

    public V remove(K key) {
        checkNotNull(key, "key");
        synchronized (lock) {
            Node<K, V> n = find(order, root, key);
            if (n == null) {
                return null;
            }
            Removal<K, V> r = delete(root, key);
            root = blacken(r.node);
            return n.value;
        }
    }

    public boolean containsKey(K key) {
        checkNotNull(key, "key");
        synchronized (lock) {
            return find(order, root, key) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            checkIndex(index, sizeOf(root));
            return selectEntry(root, index);
        }
    }

    public int rank(K key) {
        checkNotNull(key, "key");
        synchronized (lock) {
            int acc = 0;
            Node<K, V> t = root;
            while (t != null) {
                int c = order.compare(key, t.key);
                if (c <= 0) {
                    if (c == 0) {
                        return acc + sizeOf(t.left);
                    }
                    t = t.left;
                } else {
                    acc += sizeOf(t.left) + 1;
                    t = t.right;
                }
            }
            return acc;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new Snapshot<K, V>(order, root);
        }
    }

    // ---------------------------------------------------------------- insertion

    private Node<K, V> insert(Node<K, V> t, K key, V value) {
        if (t == null) {
            return new Node<K, V>(true, null, key, value, null);
        }
        int c = order.compare(key, t.key);
        if (c < 0) {
            return balance(t.red, insert(t.left, key, value), t.key, t.value, t.right);
        }
        if (c > 0) {
            return balance(t.red, t.left, t.key, t.value, insert(t.right, key, value));
        }
        // Replacement: retain the original key representative.
        return new Node<K, V>(t.red, t.left, t.key, value, t.right);
    }

    /** Okasaki-style balance that repairs a red-red violation under a black node. */
    private static <K, V> Node<K, V> balance(boolean red, Node<K, V> l, K k, V v, Node<K, V> r) {
        if (!red) {
            if (isRed(l) && isRed(l.left)) {
                return new Node<K, V>(true, blacken(l.left), l.key, l.value,
                        new Node<K, V>(false, l.right, k, v, r));
            }
            if (isRed(l) && isRed(l.right)) {
                return new Node<K, V>(true,
                        new Node<K, V>(false, l.left, l.key, l.value, l.right.left),
                        l.right.key, l.right.value,
                        new Node<K, V>(false, l.right.right, k, v, r));
            }
            if (isRed(r) && isRed(r.left)) {
                return new Node<K, V>(true,
                        new Node<K, V>(false, l, k, v, r.left.left),
                        r.left.key, r.left.value,
                        new Node<K, V>(false, r.left.right, r.key, r.value, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                return new Node<K, V>(true,
                        new Node<K, V>(false, l, k, v, r.left),
                        r.key, r.value, blacken(r.right));
            }
        }
        return new Node<K, V>(red, l, k, v, r);
    }

    // ---------------------------------------------------------------- deletion

    /** Precondition: key is present in the subtree t. */
    private Removal<K, V> delete(Node<K, V> t, K key) {
        if (t == null) {
            throw new IllegalStateException("key not present");
        }
        int c = order.compare(key, t.key);
        if (c < 0) {
            Removal<K, V> r = delete(t.left, key);
            return r.shortened
                    ? balanceLeftShort(t.red, r.node, t.key, t.value, t.right)
                    : new Removal<K, V>(new Node<K, V>(t.red, r.node, t.key, t.value, t.right), false);
        }
        if (c > 0) {
            Removal<K, V> r = delete(t.right, key);
            return r.shortened
                    ? balanceRightShort(t.red, t.left, t.key, t.value, r.node)
                    : new Removal<K, V>(new Node<K, V>(t.red, t.left, t.key, t.value, r.node), false);
        }
        return removeHere(t);
    }

    private Removal<K, V> removeHere(Node<K, V> t) {
        if (t.left == null) {
            return removeSingle(t.red, t.right);
        }
        if (t.right == null) {
            return removeSingle(t.red, t.left);
        }
        Node<K, V> min = minNode(t.right);
        Removal<K, V> r = deleteMin(t.right);
        if (r.shortened) {
            return balanceRightShort(t.red, t.left, min.key, min.value, r.node);
        }
        return new Removal<K, V>(new Node<K, V>(t.red, t.left, min.key, min.value, r.node), false);
    }

    /** Removes the minimum node of a non-null subtree. */
    private Removal<K, V> deleteMin(Node<K, V> t) {
        if (t.left == null) {
            return removeSingle(t.red, t.right);
        }
        Removal<K, V> r = deleteMin(t.left);
        return r.shortened
                ? balanceLeftShort(t.red, r.node, t.key, t.value, t.right)
                : new Removal<K, V>(new Node<K, V>(t.red, r.node, t.key, t.value, t.right), false);
    }

    /** Removes a node with at most one child. A lone child must be a red leaf. */
    private static <K, V> Removal<K, V> removeSingle(boolean red, Node<K, V> child) {
        if (child == null) {
            return new Removal<K, V>(null, !red);
        }
        return new Removal<K, V>(blacken(child), false);
    }

    private static <K, V> Node<K, V> minNode(Node<K, V> t) {
        Node<K, V> n = t;
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    /**
     * Left subtree l has lost one black level. Sibling r is non-null with the
     * original black height. Returns the repaired subtree and whether it is
     * still short by one.
     */
    private Removal<K, V> balanceLeftShort(boolean red, Node<K, V> l, K k, V v, Node<K, V> r) {
        if (isRed(r)) {
            // Rotate left, then repair the new left subtree whose sibling is black.
            Removal<K, V> t = balanceLeftShort(true, l, k, v, r.left);
            return new Removal<K, V>(new Node<K, V>(false, t.node, r.key, r.value, r.right), t.shortened);
        }
        if (isRed(r.right)) {
            return new Removal<K, V>(
                    new Node<K, V>(red, new Node<K, V>(false, l, k, v, r.left),
                            r.key, r.value, blacken(r.right)),
                    false);
        }
        if (isRed(r.left)) {
            Node<K, V> rl = r.left;
            Node<K, V> s = new Node<K, V>(false, rl.left, rl.key, rl.value,
                    new Node<K, V>(true, rl.right, r.key, r.value, r.right));
            return balanceLeftShort(red, l, k, v, s);
        }
        return new Removal<K, V>(
                new Node<K, V>(false, l, k, v, new Node<K, V>(true, r.left, r.key, r.value, r.right)),
                !red);
    }

    /** Mirror image of balanceLeftShort: right subtree r is short, sibling l is non-null. */
    private Removal<K, V> balanceRightShort(boolean red, Node<K, V> l, K k, V v, Node<K, V> r) {
        if (isRed(l)) {
            Removal<K, V> t = balanceRightShort(true, l.right, k, v, r);
            return new Removal<K, V>(new Node<K, V>(false, l.left, l.key, l.value, t.node), t.shortened);
        }
        if (isRed(l.left)) {
            return new Removal<K, V>(
                    new Node<K, V>(red, blacken(l.left), l.key, l.value,
                            new Node<K, V>(false, l.right, k, v, r)),
                    false);
        }
        if (isRed(l.right)) {
            Node<K, V> lr = l.right;
            Node<K, V> s = new Node<K, V>(false,
                    new Node<K, V>(true, l.left, l.key, l.value, lr.left),
                    lr.key, lr.value, lr.right);
            return balanceRightShort(red, s, k, v, r);
        }
        return new Removal<K, V>(
                new Node<K, V>(false, new Node<K, V>(true, l.left, l.key, l.value, l.right), k, v, r),
                !red);
    }

    // ---------------------------------------------------------------- helpers

    private static <K, V> Node<K, V> find(Order<K> order, Node<K, V> t, K key) {
        Node<K, V> n = t;
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c < 0) {
                n = n.left;
            } else if (c > 0) {
                n = n.right;
            } else {
                return n;
            }
        }
        return null;
    }

    private static <K, V> Entry<K, V> selectEntry(Node<K, V> t, int index) {
        Node<K, V> n = t;
        int i = index;
        while (true) {
            int ls = sizeOf(n.left);
            if (i < ls) {
                n = n.left;
            } else if (i == ls) {
                return new Entry<K, V>(n.key, n.value);
            } else {
                i -= ls + 1;
                n = n.right;
            }
        }
    }

    private static <K, V> boolean isRed(Node<K, V> n) {
        return n != null && n.red;
    }

    private static <K, V> int sizeOf(Node<K, V> n) {
        return n == null ? 0 : n.size;
    }

    private static <K, V> Node<K, V> blacken(Node<K, V> n) {
        if (n == null || !n.red) {
            return n;
        }
        return new Node<K, V>(false, n.left, n.key, n.value, n.right);
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " out of range for size " + size);
        }
    }

    private static <T> T checkNotNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }
}
