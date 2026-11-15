interface Order<T> {
    int compare(T left, T right);
}

/**
 * Concurrent indexed red-black-tree multiset.
 *
 * Policy: no operation blocks. Operations that cannot succeed fail fast:
 * add throws IllegalStateException if the total size would overflow int,
 * get throws IndexOutOfBoundsException for invalid indices, and remove
 * returns false when the item is absent. All live operations take a single
 * collection-wide monitor. The tree is persistent (path copying), so a
 * snapshot is captured in O(1) by sharing the current root, and snapshots
 * are read without locking because their nodes are immutable.
 */
@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}


    /** Immutable persistent red-black node. All fields are final for safe publication. */
    static final class Node<E> {
        final E elem;
        final int count;     // occurrences of this class, always >= 1
        final int total;     // occurrences in this subtree
        final int distinct;  // distinct classes in this subtree
        final boolean red;
        final Node<E> left;
        final Node<E> right;

        Node(boolean red, Node<E> left, E elem, int count, Node<E> right) {
            this.red = red;
            this.left = left;
            this.elem = elem;
            this.count = count;
            this.right = right;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }

        static int total(Node<?> n) {
            return n == null ? 0 : n.total;
        }

        static int distinct(Node<?> n) {
            return n == null ? 0 : n.distinct;
        }

        static boolean isRed(Node<?> n) {
            return n != null && n.red;
        }
    }

    /** Result of a deletion step: new subtree, whether its black height dropped by one, and extracted min data. */
    static final class Res<E> {
        final Node<E> node;
        final boolean shorter;
        final E key;
        final int cnt;

        Res(Node<E> node, boolean shorter, E key, int cnt) {
            this.node = node;
            this.shorter = shorter;
            this.key = key;
            this.cnt = cnt;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        checkNonNull(item);
        synchronized (lock) {
            if (Node.total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("size would overflow int");
            }
            root = blacken(insert(root, item));
        }
    }

    public boolean remove(T item) {
        checkNonNull(item);
        synchronized (lock) {
            if (countOf(item) == 0) {
                return false;
            }
            Res<T> r = delete(root, item);
            root = blacken(r.node);
            return true;
        }
    }

    public int count(T item) {
        checkNonNull(item);
        synchronized (lock) {
            return countOf(item);
        }
    }

    public int size() {
        synchronized (lock) {
            return Node.total(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return Node.distinct(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        checkNonNull(item);
        synchronized (lock) {
            int sum = 0;
            Node<T> n = root;
            while (n != null) {
                int c = order.compare(item, n.elem);
                if (c == 0) {
                    sum += Node.total(n.left);
                    break;
                }
                if (c < 0) {
                    n = n.left;
                } else {
                    sum += Node.total(n.left) + n.count;
                    n = n.right;
                }
            }
            return sum;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(order, root);
        }
    }

    // ---------- internal helpers (called with lock held) ----------

    private static void checkNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("null element or argument");
        }
    }

    private int countOf(T item) {
        Node<T> n = root;
        while (n != null) {
            int c = order.compare(item, n.elem);
            if (c == 0) {
                return n.count;
            }
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    static <E> E select(Node<E> root, int index) {
        if (index < 0 || index >= Node.total(root)) {
            throw new IndexOutOfBoundsException("index " + index);
        }
        Node<E> n = root;
        int idx = index;
        while (true) {
            int lt = Node.total(n.left);
            if (idx < lt) {
                n = n.left;
            } else if (idx < lt + n.count) {
                return n.elem;
            } else {
                idx -= lt + n.count;
                n = n.right;
            }
        }
    }

    static <E> Node<E> blacken(Node<E> n) {
        if (n == null || !n.red) {
            return n;
        }
        return new Node<>(false, n.left, n.elem, n.count, n.right);
    }

    private Node<T> insert(Node<T> n, T x) {
        if (n == null) {
            return new Node<>(true, null, x, 1, null);
        }
        int c = order.compare(x, n.elem);
        if (c < 0) {
            return balance(n.red, insert(n.left, x), n.elem, n.count, n.right);
        }
        if (c > 0) {
            return balance(n.red, n.left, n.elem, n.count, insert(n.right, x));
        }
        return new Node<>(n.red, n.left, n.elem, n.count + 1, n.right);
    }

    /** Okasaki-style balance for insertion: repairs red-red violations under a black node. */
    private static <E> Node<E> balance(boolean red, Node<E> l, E e, int cnt, Node<E> r) {
        if (!red) {
            if (Node.isRed(l)) {
                if (Node.isRed(l.left)) {
                    Node<E> p = l.left;
                    return new Node<>(true,
                            new Node<>(false, p.left, p.elem, p.count, p.right),
                            l.elem, l.count,
                            new Node<>(false, l.right, e, cnt, r));
                }
                if (Node.isRed(l.right)) {
                    Node<E> p = l.right;
                    return new Node<>(true,
                            new Node<>(false, l.left, l.elem, l.count, p.left),
                            p.elem, p.count,
                            new Node<>(false, p.right, e, cnt, r));
                }
            }
            if (Node.isRed(r)) {
                if (Node.isRed(r.left)) {
                    Node<E> p = r.left;
                    return new Node<>(true,
                            new Node<>(false, l, e, cnt, p.left),
                            p.elem, p.count,
                            new Node<>(false, p.right, r.elem, r.count, r.right));
                }
                if (Node.isRed(r.right)) {
                    Node<E> p = r.right;
                    return new Node<>(true,
                            new Node<>(false, l, e, cnt, r.left),
                            r.elem, r.count,
                            new Node<>(false, p.left, p.elem, p.count, p.right));
                }
            }
        }
        return new Node<>(red, l, e, cnt, r);
    }

    /**
     * Fixes a subtree whose left child has lost one unit of black height
     * (bh(l) == bh(r) - 1). r is non-null. Returns the repaired subtree and
     * whether its own black height dropped by one.
     */
    static <E> Res<E> fixLeft(boolean red, Node<E> l, E e, int cnt, Node<E> r) {
        if (Node.isRed(r)) {
            // Case 1: sibling red (so this node is black); rotate left and repair recursively.
            Res<E> a = fixLeft(true, l, e, cnt, r.left);
            return new Res<>(new Node<>(false, a.node, r.elem, r.count, r.right), false, null, 0);
        }
        if (Node.isRed(r.right)) {
            // Case 2a: sibling black, far nephew red.
            Node<E> rr = r.right;
            return new Res<>(
                    new Node<>(red,
                            new Node<>(false, l, e, cnt, r.left),
                            r.elem, r.count,
                            blacken(rr)),
                    false, null, 0);
        }
        if (Node.isRed(r.left)) {
            // Case 2b: sibling black, near nephew red; double rotation.
            Node<E> rl = r.left;
            return new Res<>(
                    new Node<>(red,
                            new Node<>(false, l, e, cnt, rl.left),
                            rl.elem, rl.count,
                            new Node<>(false, rl.right, r.elem, r.count, r.right)),
                    false, null, 0);
        }
        // Case 2c: sibling black with black children; recolor sibling red.
        Node<E> newR = new Node<>(true, r.left, r.elem, r.count, r.right);
        return new Res<>(new Node<>(false, l, e, cnt, newR), !red, null, 0);
    }

    /**
     * Mirror of fixLeft: right child has lost one unit of black height
     * (bh(r) == bh(l) - 1). l is non-null.
     */
    static <E> Res<E> fixRight(boolean red, Node<E> l, E e, int cnt, Node<E> r) {
        if (Node.isRed(l)) {
            Res<E> a = fixRight(true, l.right, e, cnt, r);
            return new Res<>(new Node<>(false, l.left, l.elem, l.count, a.node), false, null, 0);
        }
        if (Node.isRed(l.left)) {
            Node<E> ll = l.left;
            return new Res<>(
                    new Node<>(red,
                            blacken(ll),
                            l.elem, l.count,
                            new Node<>(false, l.right, e, cnt, r)),
                    false, null, 0);
        }
        if (Node.isRed(l.right)) {
            Node<E> lr = l.right;
            return new Res<>(
                    new Node<>(red,
                            new Node<>(false, l.left, l.elem, l.count, lr.left),
                            lr.elem, lr.count,
                            new Node<>(false, lr.right, e, cnt, r)),
                    false, null, 0);
        }
        Node<E> newL = new Node<>(true, l.left, l.elem, l.count, l.right);
        return new Res<>(new Node<>(false, newL, e, cnt, r), !red, null, 0);
    }

    /** Deletes one occurrence of key, which must be present. */
    private Res<T> delete(Node<T> n, T key) {
        int c = order.compare(key, n.elem);
        if (c < 0) {
            Res<T> s = delete(n.left, key);
            if (s.shorter) {
                return fixLeft(n.red, s.node, n.elem, n.count, n.right);
            }
            return new Res<>(new Node<>(n.red, s.node, n.elem, n.count, n.right), false, null, 0);
        }
        if (c > 0) {
            Res<T> s = delete(n.right, key);
            if (s.shorter) {
                return fixRight(n.red, n.left, n.elem, n.count, s.node);
            }
            return new Res<>(new Node<>(n.red, n.left, n.elem, n.count, s.node), false, null, 0);
        }
        if (n.count > 1) {
            return new Res<>(new Node<>(n.red, n.left, n.elem, n.count - 1, n.right), false, null, 0);
        }
        return removeNode(n);
    }

    /** Removes a node entirely (its class count reaches zero). */
    private Res<T> removeNode(Node<T> n) {
        if (n.left == null) {
            if (n.right == null) {
                return new Res<>(null, !n.red, null, 0);
            }
            return new Res<>(blacken(n.right), false, null, 0);
        }
        if (n.right == null) {
            return new Res<>(blacken(n.left), false, null, 0);
        }
        Res<T> m = deleteMin(n.right);
        if (m.shorter) {
            return fixRight(n.red, n.left, m.key, m.cnt, m.node);
        }
        return new Res<>(new Node<>(n.red, n.left, m.key, m.cnt, m.node), false, null, 0);
    }

    /** Removes the minimum node of a non-null subtree, reporting its element and count. */
    private Res<T> deleteMin(Node<T> n) {
        if (n.left == null) {
            Node<T> rep = n.right == null ? null : blacken(n.right);
            return new Res<>(rep, n.right == null && !n.red, n.elem, n.count);
        }
        Res<T> s = deleteMin(n.left);
        Res<T> out;
        if (s.shorter) {
            out = fixLeft(n.red, s.node, n.elem, n.count, n.right);
        } else {
            out = new Res<>(new Node<>(n.red, s.node, n.elem, n.count, n.right), false, null, 0);
        }
        return new Res<>(out.node, out.shorter, s.key, s.cnt);
    }
}

/** Immutable historical view of a TreeBag captured in O(1). */
@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final TreeBag.Node<T> root;

    Snapshot(Order<T> order, TreeBag.Node<T> root) {
        this.order = order;
        this.root = root;
    }

    public int size() {
        return TreeBag.Node.total(root);
    }

    public int distinctSize() {
        return TreeBag.Node.distinct(root);
    }

    public int count(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        TreeBag.Node<T> n = root;
        while (n != null) {
            int c = order.compare(item, n.elem);
            if (c == 0) {
                return n.count;
            }
            n = c < 0 ? n.left : n.right;
        }
        return 0;
    }

    public T get(int index) {
        return TreeBag.select(root, index);
    }
}
