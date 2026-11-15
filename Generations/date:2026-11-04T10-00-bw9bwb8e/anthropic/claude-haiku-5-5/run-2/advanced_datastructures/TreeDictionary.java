@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    /**
     * Total, deterministic ordering over keys. Implementations must not mutate
     * the collection or call back into it.
     */
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
    }

    /**
     * Read-only view of one historical state. Captured in O(1) by sharing the
     * immutable persistent tree. Never blocks and needs no locking.
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
            requireNonNull(key, "key");
            Node<K, V> n = find(root, key, order);
            return n == null ? null : n.value;
        }

        public Entry<K, V> entryAt(int index) {
            return selectEntry(root, index);
        }
    }

    // ------------------------------------------------------------------
    // Persistent (immutable) red-black tree nodes.
    // Null represents a black leaf. Every node stores its subtree size.
    // ------------------------------------------------------------------
    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int size;

        Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = sizeOf(left) + sizeOf(right) + 1;
        }
    }

    /** Result of a deletion step: new subtree plus whether its black height dropped by one. */
    private static final class Res<K, V> {
        final Node<K, V> node;
        final boolean deficit;

        Res(Node<K, V> node, boolean deficit) {
            this.node = node;
            this.deficit = deficit;
        }
    }

    // ------------------------------------------------------------------
    // Concurrency policy
    // A single collection-wide monitor guards the root reference. All public
    // operations complete immediately (no blocking, no waiting): get/remove/
    // containsKey return null/false when absent, put never fails for capacity,
    // and entryAt/rank throw IndexOutOfBoundsException for invalid indices.
    // Because nodes are immutable, snapshots and entries need no locking.
    // ------------------------------------------------------------------
    private final Order<K> order;
    private final Object lock = new Object();
    private Node<K, V> root; // guarded by lock

    public TreeDictionary(Order<K> order) {
        requireNonNull(order, "order");
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (lock) {
            Node<K, V> existing = find(root, key, order);
            if (existing != null) {
                V old = existing.value;
                root = replaceValue(root, key, value, order);
                return old;
            }
            root = blacken(insert(root, key, value, order));
            return null;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            Node<K, V> n = find(root, key, order);
            return n == null ? null : n.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
                return null;
            }
            V old = existing.value;
            Res<K, V> r = delete(root, key, order);
            root = blacken(r.node);
            return old;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            return selectEntry(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            int r = 0;
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c <= 0) {
                    n = n.left;
                } else {
                    r += sizeOf(n.left) + 1;
                    n = n.right;
                }
            }
            return r;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(order, root);
        }
    }

    // ------------------------------------------------------------------
    // Shared static helpers (pure functions over immutable nodes)
    // ------------------------------------------------------------------
    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }

    private static <K, V> int sizeOf(Node<K, V> n) {
        return n == null ? 0 : n.size;
    }

    private static <K, V> boolean isRed(Node<K, V> n) {
        return n != null && n.red;
    }

    private static <K, V> Node<K, V> blacken(Node<K, V> n) {
        if (n == null || !n.red) {
            return n;
        }
        return new Node<>(n.key, n.value, false, n.left, n.right);
    }

    private static <K, V> Node<K, V> find(Node<K, V> n, K key, Order<K> ord) {
        while (n != null) {
            int c = ord.compare(key, n.key);
            if (c == 0) {
                return n;
            }
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    private static <K, V> Entry<K, V> selectEntry(Node<K, V> n, int index) {
        if (index < 0 || index >= sizeOf(n)) {
            throw new IndexOutOfBoundsException("index " + index + " out of range");
        }
        while (true) {
            int ls = sizeOf(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return new Entry<>(n.key, n.value);
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
    }

    /** Path-copying replacement of an existing key's value; key representative and structure kept. */
    private static <K, V> Node<K, V> replaceValue(Node<K, V> n, K key, V value, Order<K> ord) {
        int c = ord.compare(key, n.key);
        if (c == 0) {
            return new Node<>(n.key, value, n.red, n.left, n.right);
        }
        if (c < 0) {
            return new Node<>(n.key, n.value, n.red, replaceValue(n.left, key, value, ord), n.right);
        }
        return new Node<>(n.key, n.value, n.red, n.left, replaceValue(n.right, key, value, ord));
    }

    // ------------------------------------------------------------------
    // Insertion (Okasaki-style functional balancing with path copying)
    // ------------------------------------------------------------------
    private static <K, V> Node<K, V> insert(Node<K, V> n, K key, V value, Order<K> ord) {
        if (n == null) {
            return new Node<>(key, value, true, null, null);
        }
        int c = ord.compare(key, n.key);
        if (c < 0) {
            return balance(n.red, n.key, n.value, insert(n.left, key, value, ord), n.right);
        }
        return balance(n.red, n.key, n.value, n.left, insert(n.right, key, value, ord));
    }

    private static <K, V> Node<K, V> balance(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        if (red) {
            return new Node<>(k, v, true, l, r);
        }
        if (isRed(l) && isRed(l.left)) {
            Node<K, V> x = l.left;
            return new Node<>(l.key, l.value, true,
                    new Node<>(x.key, x.value, false, x.left, x.right),
                    new Node<>(k, v, false, l.right, r));
        }
        if (isRed(l) && isRed(l.right)) {
            Node<K, V> y = l.right;
            return new Node<>(y.key, y.value, true,
                    new Node<>(l.key, l.value, false, l.left, y.left),
                    new Node<>(k, v, false, y.right, r));
        }
        if (isRed(r) && isRed(r.left)) {
            Node<K, V> y = r.left;
            return new Node<>(y.key, y.value, true,
                    new Node<>(k, v, false, l, y.left),
                    new Node<>(r.key, r.value, false, y.right, r.right));
        }
        if (isRed(r) && isRed(r.right)) {
            Node<K, V> z = r.right;
            return new Node<>(r.key, r.value, true,
                    new Node<>(k, v, false, l, r.left),
                    new Node<>(z.key, z.value, false, z.left, z.right));
        }
        return new Node<>(k, v, false, l, r);
    }

    // ------------------------------------------------------------------
    // Deletion (functional, with black-height deficit propagation).
    // Precondition: the key exists in the subtree.
    // ------------------------------------------------------------------
    private static <K, V> Res<K, V> delete(Node<K, V> n, K key, Order<K> ord) {
        int c = ord.compare(key, n.key);
        if (c < 0) {
            Res<K, V> r = delete(n.left, key, ord);
            if (!r.deficit) {
                return new Res<>(new Node<>(n.key, n.value, n.red, r.node, n.right), false);
            }
            return fixLeft(n.red, n.key, n.value, r.node, n.right);
        }
        if (c > 0) {
            Res<K, V> r = delete(n.right, key, ord);
            if (!r.deficit) {
                return new Res<>(new Node<>(n.key, n.value, n.red, n.left, r.node), false);
            }
            return fixRight(n.red, n.key, n.value, n.left, r.node);
        }
        return removeHere(n);
    }

    /** Removes the node n itself, returning the replacement subtree. */
    private static <K, V> Res<K, V> removeHere(Node<K, V> n) {
        if (n.left == null) {
            if (n.right == null) {
                return new Res<>(null, !n.red);
            }
            // Black node with a red leaf as its only child.
            return new Res<>(blacken(n.right), false);
        }
        if (n.right == null) {
            // Black node with a red leaf as its only child.
            return new Res<>(blacken(n.left), false);
        }
        Node<K, V> m = minNode(n.right);
        Res<K, V> r = removeMin(n.right);
        if (!r.deficit) {
            return new Res<>(new Node<>(m.key, m.value, n.red, n.left, r.node), false);
        }
        return fixRight(n.red, m.key, m.value, n.left, r.node);
    }

    /** Removes the minimum node of n's subtree. */
    private static <K, V> Res<K, V> removeMin(Node<K, V> n) {
        if (n.left == null) {
            return removeHere(n);
        }
        Res<K, V> r = removeMin(n.left);
        if (!r.deficit) {
            return new Res<>(new Node<>(n.key, n.value, n.red, r.node, n.right), false);
        }
        return fixLeft(n.red, n.key, n.value, r.node, n.right);
    }

    private static <K, V> Node<K, V> minNode(Node<K, V> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    /**
     * Builds a node of color 'red' with left child L (black height one less than R)
     * and right child R. Returns the new subtree and whether its black height is
     * still one less than the original.
     */
    private static <K, V> Res<K, V> fixLeft(boolean red, K k, V v, Node<K, V> L, Node<K, V> R) {
        if (R.red) {
            // Rotate: sibling R (black) becomes top; fix the inner part with a red parent.
            Node<K, V> inner = fixLeft(true, k, v, L, R.left).node;
            return new Res<>(new Node<>(R.key, R.value, false, inner, R.right), false);
        }
        if (!isRed(R.left) && !isRed(R.right)) {
            Node<K, V> newR = new Node<>(R.key, R.value, true, R.left, R.right);
            return new Res<>(new Node<>(k, v, false, L, newR), !red);
        }
        if (isRed(R.right)) {
            Node<K, V> far = R.right;
            Node<K, V> top = new Node<>(R.key, R.value, red,
                    new Node<>(k, v, false, L, R.left),
                    new Node<>(far.key, far.value, false, far.left, far.right));
            return new Res<>(top, false);
        }
        // R.left red, R.right black
        Node<K, V> rl = R.left;
        Node<K, V> top = new Node<>(rl.key, rl.value, red,
                new Node<>(k, v, false, L, rl.left),
                new Node<>(R.key, R.value, false, rl.right, R.right));
        return new Res<>(top, false);
    }

    /** Mirror image of fixLeft: R has the black-height deficit, L is the sibling. */
    private static <K, V> Res<K, V> fixRight(boolean red, K k, V v, Node<K, V> L, Node<K, V> R) {
        if (L.red) {
            Node<K, V> inner = fixRight(true, k, v, L.right, R).node;
            return new Res<>(new Node<>(L.key, L.value, false, L.left, inner), false);
        }
        if (!isRed(L.left) && !isRed(L.right)) {
            Node<K, V> newL = new Node<>(L.key, L.value, true, L.left, L.right);
            return new Res<>(new Node<>(k, v, false, newL, R), !red);
        }
        if (isRed(L.left)) {
            Node<K, V> far = L.left;
            Node<K, V> top = new Node<>(L.key, L.value, red,
                    new Node<>(far.key, far.value, false, far.left, far.right),
                    new Node<>(k, v, false, L.right, R));
            return new Res<>(top, false);
        }
        // L.right red, L.left black
        Node<K, V> lr = L.right;
        Node<K, V> top = new Node<>(lr.key, lr.value, red,
                new Node<>(L.key, L.value, false, L.left, lr.left),
                new Node<>(k, v, false, lr.right, R));
        return new Res<>(top, false);
    }

    // ------------------------------------------------------------------
    // Generic helpers used by Snapshot as well
    // ------------------------------------------------------------------
}
