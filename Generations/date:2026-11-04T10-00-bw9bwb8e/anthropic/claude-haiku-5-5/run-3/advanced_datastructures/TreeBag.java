interface Order<T> {
    int compare(T left, T right);
}

interface Snapshot<T> {
    int size();
    int distinctSize();
    int count(T item);
    T get(int index);
}

/*
 * Concurrency policy: every public operation runs under one collection-wide
 * monitor (a private lock object) and never blocks or waits. Operations that
 * cannot succeed report it immediately: remove returns false when the item is
 * absent, count returns 0, get throws IndexOutOfBoundsException for an invalid
 * index (including on an empty bag), and add throws IllegalStateException when
 * the total size would overflow int. Mutations build new nodes along a copied
 * path and only publish the new root after all comparisons succeed, so a
 * throwing comparator leaves the bag unchanged. Nodes are immutable, so
 * snapshots can be read without holding the lock.
 */
@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final boolean red;
        final Node<T> left;
        final T key;
        final int count;
        final Node<T> right;
        final int total;
        final int distinct;

        Node(boolean red, Node<T> left, T key, int count, Node<T> right) {
            this.red = red;
            this.left = left;
            this.key = key;
            this.count = count;
            this.right = right;
            this.total = totalOf(left) + count + totalOf(right);
            this.distinct = distinctOf(left) + 1 + distinctOf(right);
        }
    }

    private static final class Res<E> {
        final Node<E> node;
        final boolean shorted;

        Res(Node<E> node, boolean shorted) {
            this.node = node;
            this.shorted = shorted;
        }
    }

    private static final class SnapshotImpl<T> implements Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;

        SnapshotImpl(Order<T> order, Node<T> root) {
            this.order = order;
            this.root = root;
        }

        @Override
        public int size() {
            return totalOf(root);
        }

        @Override
        public int distinctSize() {
            return distinctOf(root);
        }

        @Override
        public int count(T item) {
            requireNonNull(item, "item");
            Node<T> n = find(order, root, item);
            return n == null ? 0 : n.count;
        }

        @Override
        public T get(int index) {
            return selectAt(root, index);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeBag(Order<T> order) {
        requireNonNull(order, "order");
        this.order = order;
    }

    public void add(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            if (totalOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("bag size would overflow int");
            }
            Node<T> updated = insert(order, root, item);
            root = blackenNullable(updated);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            Node<T> n = find(order, root, item);
            if (n == null) {
                return false;
            }
            if (n.count > 1) {
                root = decrement(order, root, item);
            } else {
                Res<T> r = delete(order, root, item);
                root = blackenNullable(r.node);
            }
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            Node<T> n = find(order, root, item);
            return n == null ? 0 : n.count;
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
            return selectAt(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item, "item");
        synchronized (lock) {
            int result = 0;
            Node<T> n = root;
            while (n != null) {
                int c = order.compare(item, n.key);
                if (c <= 0) {
                    n = n.left;
                } else {
                    result += totalOf(n.left) + n.count;
                    n = n.right;
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<T>(order, root);
        }
    }

    // ----- static helpers (all immutable-structure operations) -----

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

    private static <E> Node<E> blackenNullable(Node<E> n) {
        return n == null ? null : blacken(n);
    }

    private static <E> Node<E> blacken(Node<E> n) {
        return n.red ? new Node<E>(false, n.left, n.key, n.count, n.right) : n;
    }

    private static <E> Node<E> redden(Node<E> n) {
        return n.red ? n : new Node<E>(true, n.left, n.key, n.count, n.right);
    }

    private static <E> Node<E> find(Order<E> o, Node<E> n, E item) {
        while (n != null) {
            int c = o.compare(item, n.key);
            if (c == 0) {
                return n;
            }
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    private static <E> E selectAt(Node<E> root, int index) {
        if (index < 0 || index >= totalOf(root)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        Node<E> n = root;
        int i = index;
        while (true) {
            int lt = totalOf(n.left);
            if (i < lt) {
                n = n.left;
            } else if (i < lt + n.count) {
                return n.key;
            } else {
                i -= lt + n.count;
                n = n.right;
            }
        }
    }

    private static <E> Node<E> minNode(Node<E> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    /** Okasaki-style balance: repairs a red-red violation under a black node. */
    private static <E> Node<E> balance(boolean red, Node<E> l, E k, int c, Node<E> r) {
        if (!red) {
            if (isRed(l) && isRed(l.left)) {
                Node<E> a = l.left;
                return new Node<E>(true,
                        new Node<E>(false, a.left, a.key, a.count, a.right),
                        l.key, l.count,
                        new Node<E>(false, l.right, k, c, r));
            }
            if (isRed(l) && isRed(l.right)) {
                Node<E> b = l.right;
                return new Node<E>(true,
                        new Node<E>(false, l.left, l.key, l.count, b.left),
                        b.key, b.count,
                        new Node<E>(false, b.right, k, c, r));
            }
            if (isRed(r) && isRed(r.left)) {
                Node<E> b = r.left;
                return new Node<E>(true,
                        new Node<E>(false, l, k, c, b.left),
                        b.key, b.count,
                        new Node<E>(false, b.right, r.key, r.count, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                Node<E> b = r.right;
                return new Node<E>(true,
                        new Node<E>(false, l, k, c, r.left),
                        r.key, r.count,
                        new Node<E>(false, b.left, b.key, b.count, b.right));
            }
        }
        return new Node<E>(red, l, k, c, r);
    }

    /** Inserts one occurrence of item; path-copies, never mutates existing nodes. */
    private static <E> Node<E> insert(Order<E> o, Node<E> n, E item) {
        if (n == null) {
            return new Node<E>(true, null, item, 1, null);
        }
        int c = o.compare(item, n.key);
        if (c < 0) {
            return balance(n.red, insert(o, n.left, item), n.key, n.count, n.right);
        }
        if (c > 0) {
            return balance(n.red, n.left, n.key, n.count, insert(o, n.right, item));
        }
        return new Node<E>(n.red, n.left, n.key, n.count + 1, n.right);
    }

    /** Decrements the count of an existing item with count > 1. */
    private static <E> Node<E> decrement(Order<E> o, Node<E> n, E item) {
        int c = o.compare(item, n.key);
        if (c < 0) {
            return new Node<E>(n.red, decrement(o, n.left, item), n.key, n.count, n.right);
        }
        if (c > 0) {
            return new Node<E>(n.red, n.left, n.key, n.count, decrement(o, n.right, item));
        }
        return new Node<E>(n.red, n.left, n.key, n.count - 1, n.right);
    }

    /** Structurally removes the whole node for item (item must be present). */
    private static <E> Res<E> delete(Order<E> o, Node<E> n, E item) {
        int c = o.compare(item, n.key);
        if (c < 0) {
            Res<E> r = delete(o, n.left, item);
            return fixLeft(n.red, r.node, n.key, n.count, n.right, r.shorted);
        }
        if (c > 0) {
            Res<E> r = delete(o, n.right, item);
            return fixRight(n.red, n.left, n.key, n.count, r.node, r.shorted);
        }
        if (n.left == null && n.right == null) {
            return new Res<E>(null, !n.red);
        }
        if (n.left == null) {
            return new Res<E>(blacken(n.right), false);
        }
        if (n.right == null) {
            return new Res<E>(blacken(n.left), false);
        }
        Node<E> m = minNode(n.right);
        Res<E> r = delete(o, n.right, m.key);
        return fixRight(n.red, n.left, m.key, m.count, r.node, r.shorted);
    }

    private static <E> Res<E> fixLeft(boolean red, Node<E> l, E k, int c, Node<E> r, boolean shortL) {
        if (!shortL) {
            return new Res<E>(new Node<E>(red, l, k, c, r), false);
        }
        return leftDeficit(red, l, k, c, r);
    }

    private static <E> Res<E> fixRight(boolean red, Node<E> l, E k, int c, Node<E> r, boolean shortR) {
        if (!shortR) {
            return new Res<E>(new Node<E>(red, l, k, c, r), false);
        }
        return rightDeficit(red, l, k, c, r);
    }

    /**
     * Left subtree (l) has black height one less than sibling w (right side).
     * Parent has color red; w is its right child.
     */
    private static <E> Res<E> leftDeficit(boolean red, Node<E> l, E k, int c, Node<E> w) {
        if (w.red) {
            // Case 1: rotate left at parent, then fix the new left spine with black sibling w.left.
            Res<E> p = leftDeficit(true, l, k, c, w.left);
            return new Res<E>(new Node<E>(false, p.node, w.key, w.count, w.right), false);
        }
        if (isRed(w.right)) {
            // Case 4: black sibling with red far child.
            return new Res<E>(case4Left(red, l, k, c, w), false);
        }
        if (isRed(w.left)) {
            // Case 3: black sibling with red near child; rotate right at w, then case 4.
            Node<E> wl = w.left;
            Node<E> w2 = new Node<E>(false, wl.left, wl.key, wl.count,
                    new Node<E>(true, wl.right, w.key, w.count, w.right));
            return new Res<E>(case4Left(red, l, k, c, w2), false);
        }
        // Case 2: black sibling with black children; push the deficit upward.
        return new Res<E>(new Node<E>(false, l, k, c, redden(w)), !red);
    }

    private static <E> Node<E> case4Left(boolean red, Node<E> l, E k, int c, Node<E> s) {
        return new Node<E>(red,
                new Node<E>(false, l, k, c, s.left),
                s.key, s.count,
                blacken(s.right));
    }

    /**
     * Right subtree (r) has black height one less than sibling w (left side).
     */
    private static <E> Res<E> rightDeficit(boolean red, Node<E> w, E k, int c, Node<E> r) {
        if (w.red) {
            Res<E> p = rightDeficit(true, w.right, k, c, r);
            return new Res<E>(new Node<E>(false, w.left, w.key, w.count, p.node), false);
        }
        if (isRed(w.left)) {
            return new Res<E>(case4Right(red, w, k, c, r), false);
        }
        if (isRed(w.right)) {
            Node<E> wr = w.right;
            Node<E> w2 = new Node<E>(false,
                    new Node<E>(true, w.left, w.key, w.count, wr.left),
                    wr.key, wr.count, wr.right);
            return new Res<E>(case4Right(red, w2, k, c, r), false);
        }
        return new Res<E>(new Node<E>(false, redden(w), k, c, r), !red);
    }

    private static <E> Node<E> case4Right(boolean red, Node<E> s, E k, int c, Node<E> r) {
        return new Node<E>(red,
                blacken(s.left),
                s.key, s.count,
                new Node<E>(false, s.right, k, c, r));
    }
}
