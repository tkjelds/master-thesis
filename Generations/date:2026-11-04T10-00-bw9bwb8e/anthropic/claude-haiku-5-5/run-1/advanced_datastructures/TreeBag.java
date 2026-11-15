@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}


    /**
     * Total order over elements. Must be deterministic, total, side-effect free,
     * and must not call back into the collection.
     */
    public interface Order<E> {
        int compare(E left, E right);
    }

    /**
     * Immutable historical view of the bag. Captured in O(1) by sharing the
     * persistent root. Element references are retained, not copied.
     */
    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;

        private Snapshot(Order<E> order, Node<E> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return totalOf(root);
        }

        public int distinctSize() {
            return distinctOf(root);
        }

        public int count(E item) {
            requireNonNull(item, "item");
            return countOf(root, item, order);
        }

        public E get(int index) {
            int s = totalOf(root);
            if (index < 0 || index >= s) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + s);
            }
            return select(root, index);
        }
    }

    /*
     * Persistent red-black node. All fields are final and nodes are never
     * mutated after construction; updates copy the path from the root.
     * Snapshots share nodes with the live tree, so they are never altered.
     */
    private static final class Node<E> {
        final E key;
        final int count;      // occurrences of this class, always > 0
        final int total;      // occurrences in this subtree
        final int distinct;   // distinct classes in this subtree
        final boolean red;
        final Node<E> left;
        final Node<E> right;

        Node(boolean red, E key, int count, Node<E> left, Node<E> right) {
            this.red = red;
            this.key = key;
            this.count = count;
            this.left = left;
            this.right = right;
            this.total = count + totalOf(left) + totalOf(right);
            this.distinct = 1 + distinctOf(left) + distinctOf(right);
        }
    }

    /** Result of a deletion step: replacement subtree and whether its black height dropped by one. */
    private static final class Res<E> {
        final Node<E> node;
        final boolean shortened;

        Res(Node<E> node, boolean shortened) {
            this.node = node;
            this.shortened = shortened;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T> root;

    /*
     * Blocking policy: the bag is unbounded and never blocks. Operations that
     * cannot be satisfied (remove of an absent item, get out of range) return
     * false or throw immediately. Every public operation is linearizable under
     * a single monitor held only for the duration of the operation.
     */

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order must not be null");
        }
        this.order = order;
    }

    public void add(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            if (totalOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("size would overflow int");
            }
            // Persistent update: the old root is untouched until the assignment.
            root = paint(insertNode(root, item, order), false);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            int c = countOf(root, item, order);
            if (c == 0) {
                return false;
            }
            if (c == 1) {
                Res<T> r = del(root, item, order);
                root = blackRoot(r.node);
            } else {
                root = adjust(root, item, order, -1);
            }
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            return countOf(root, item, order);
        }
    }

    public int size() {
        synchronized (lock) {
            return totalOf(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return distinctOf(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            int s = totalOf(root);
            if (index < 0 || index >= s) {
                throw new IndexOutOfBoundsException("index: " + index + ", size: " + s);
            }
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            int r = 0;
            Node<T> n = root;
            while (n != null) {
                int c = order.compare(item, n.key);
                if (c <= 0) {
                    n = n.left;
                } else {
                    r += totalOf(n.left) + n.count;
                    n = n.right;
                }
            }
            return r;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(order, root);
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }

    private static <E> int totalOf(Node<E> n) {
        return n == null ? 0 : n.total;
    }

    private static <E> int distinctOf(Node<E> n) {
        return n == null ? 0 : n.distinct;
    }

    private static <E> boolean isRed(Node<E> n) {
        return n != null && n.red;
    }

    private static <E> Node<E> paint(Node<E> n, boolean red) {
        if (n.red == red) {
            return n;
        }
        return new Node<>(red, n.key, n.count, n.left, n.right);
    }

    private static <E> Node<E> blackRoot(Node<E> n) {
        return n == null ? null : paint(n, false);
    }

    /** Builds a node whose short-side child x is on the left if sl, otherwise on the right. */
    private static <E> Node<E> make(boolean sl, boolean red, E key, int count, Node<E> x, Node<E> y) {
        return sl ? new Node<>(red, key, count, x, y) : new Node<>(red, key, count, y, x);
    }

    private static <E> int countOf(Node<E> n, E item, Order<E> order) {
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c == 0) {
                return n.count;
            }
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    private static <E> E select(Node<E> n, int index) {
        int i = index;
        while (true) {
            int lt = totalOf(n.left);
            if (i < lt) {
                n = n.left;
            } else {
                i -= lt;
                if (i < n.count) {
                    return n.key;
                }
                i -= n.count;
                n = n.right;
            }
        }
    }

    // ----------------------------------------------------------------------
    // Insertion (Okasaki-style balance, path copying)
    // ----------------------------------------------------------------------

    private static <E> Node<E> insertNode(Node<E> n, E item, Order<E> order) {
        if (n == null) {
            return new Node<>(true, item, 1, null, null);
        }
        int c = order.compare(item, n.key);
        if (c < 0) {
            return balance(n.red, n.key, n.count, insertNode(n.left, item, order), n.right);
        }
        if (c > 0) {
            return balance(n.red, n.key, n.count, n.left, insertNode(n.right, item, order));
        }
        return new Node<>(n.red, n.key, n.count + 1, n.left, n.right);
    }

    private static <E> Node<E> balance(boolean red, E key, int count, Node<E> l, Node<E> r) {
        if (!red) {
            if (isRed(l) && isRed(l.left)) {
                Node<E> x = l.left;
                return new Node<>(true, l.key, l.count,
                        new Node<>(false, x.key, x.count, x.left, x.right),
                        new Node<>(false, key, count, l.right, r));
            }
            if (isRed(l) && isRed(l.right)) {
                Node<E> y = l.right;
                return new Node<>(true, y.key, y.count,
                        new Node<>(false, l.key, l.count, l.left, y.left),
                        new Node<>(false, key, count, y.right, r));
            }
            if (isRed(r) && isRed(r.left)) {
                Node<E> y = r.left;
                return new Node<>(true, y.key, y.count,
                        new Node<>(false, key, count, l, y.left),
                        new Node<>(false, r.key, r.count, y.right, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                Node<E> y = r.right;
                return new Node<>(true, r.key, r.count,
                        new Node<>(false, key, count, l, r.left),
                        new Node<>(false, y.key, y.count, y.left, y.right));
            }
        }
        return new Node<>(red, key, count, l, r);
    }

    /** Path-copying update of an existing occurrence count; the item must be present. */
    private static <E> Node<E> adjust(Node<E> n, E item, Order<E> order, int delta) {
        int c = order.compare(item, n.key);
        if (c < 0) {
            return new Node<>(n.red, n.key, n.count, adjust(n.left, item, order, delta), n.right);
        }
        if (c > 0) {
            return new Node<>(n.red, n.key, n.count, n.left, adjust(n.right, item, order, delta));
        }
        return new Node<>(n.red, n.key, n.count + delta, n.left, n.right);
    }

    // ----------------------------------------------------------------------
    // Deletion (removes a whole node; black-height repair via fix)
    // ----------------------------------------------------------------------

    /** Precondition: a node with a key comparing equal to item exists in n's subtree. */
    private static <E> Res<E> del(Node<E> n, E item, Order<E> order) {
        int c = order.compare(item, n.key);
        if (c < 0) {
            Res<E> r = del(n.left, item, order);
            if (!r.shortened) {
                return new Res<>(new Node<>(n.red, n.key, n.count, r.node, n.right), false);
            }
            return fix(true, n.red, n.key, n.count, r.node, n.right);
        }
        if (c > 0) {
            Res<E> r = del(n.right, item, order);
            if (!r.shortened) {
                return new Res<>(new Node<>(n.red, n.key, n.count, n.left, r.node), false);
            }
            return fix(false, n.red, n.key, n.count, r.node, n.left);
        }
        // Found the node to remove.
        if (n.left == null || n.right == null) {
            Node<E> child = n.left == null ? n.right : n.left;
            if (child == null) {
                return new Res<>(null, !n.red);
            }
            // A black node with one child has a red leaf child in a valid tree.
            return new Res<>(paint(child, false), false);
        }
        // Two children: replace with successor (min of right subtree).
        Node<E> mn = n.right;
        while (mn.left != null) {
            mn = mn.left;
        }
        Res<E> r = del(n.right, mn.key, order);
        if (!r.shortened) {
            return new Res<>(new Node<>(n.red, mn.key, mn.count, n.left, r.node), false);
        }
        return fix(false, n.red, mn.key, mn.count, r.node, n.left);
    }

    /**
     * Repairs a node whose short-side child x (on side sl) has black height one
     * less than the sibling s. Returns a valid red-black subtree and whether its
     * black height dropped by one relative to the original node.
     */
    private static <E> Res<E> fix(boolean sl, boolean red, E key, int count, Node<E> x, Node<E> s) {
        Node<E> near = sl ? s.left : s.right;
        Node<E> far = sl ? s.right : s.left;

        if (s.red) {
            // Rotate the red sibling up, then repair the same side below it.
            Res<E> y = fix(sl, true, key, count, x, near);
            if (!y.shortened) {
                return new Res<>(make(sl, false, s.key, s.count, y.node, far), false);
            }
            return fix(sl, false, s.key, s.count, y.node, far);
        }

        if (isRed(far)) {
            // Single rotation.
            Node<E> p = make(sl, false, key, count, x, near);
            Node<E> f = paint(far, false);
            return new Res<>(make(sl, red, s.key, s.count, p, f), false);
        }

        if (isRed(near)) {
            // Double rotation.
            Node<E> a = sl ? near.left : near.right;   // toward the repaired side
            Node<E> b = sl ? near.right : near.left;   // toward the far side
            Node<E> p = make(sl, false, key, count, x, a);
            Node<E> s2 = make(sl, false, s.key, s.count, b, far);
            return new Res<>(make(sl, red, near.key, near.count, p, s2), false);
        }

        // Sibling and its children all black: recolor sibling red and push deficit up.
        Node<E> sRed = paint(s, true);
        Node<E> p = make(sl, false, key, count, x, sRed);
        return new Res<>(p, !red);
    }
}
