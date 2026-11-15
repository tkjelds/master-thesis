interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet.Node<T> root;
    private final Order<T> order;

    Snapshot(TreeSet.Node<T> root, Order<T> order) {
        this.root = root;
        this.order = order;
    }

    public int size() {
        return TreeSet.sizeOf(root);
    }

    public T get(int index) {
        int n = TreeSet.sizeOf(root);
        if (index < 0 || index >= n) {
            throw new IndexOutOfBoundsException("index " + index + " out of range for size " + n);
        }
        return TreeSet.nthOf(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item must not be null");
        }
        TreeSet.Node<T> n = root;
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c == 0) {
                return true;
            }
            n = c < 0 ? n.left : n.right;
        }
        return false;
    }
}

/*
 * Concurrent indexed red-black-tree set using persistent (path-copying) nodes.
 *
 * Concurrency policy: all live operations are serialized by a single monitor
 * (the private lock object). The set is unbounded, so no operation blocks on
 * capacity. Operations on an empty set are well defined: remove and contains
 * return false, size returns 0, get throws IndexOutOfBoundsException, and
 * rank returns 0. No waiting or busy-waiting is used.
 *
 * Nodes are immutable once published. Every update copies the nodes on the
 * modified path, so a Snapshot (which just captures the root reference in O(1))
 * never observes later writes.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}


    static final class Node<E> {
        final E key;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int size;

        Node(E key, boolean red, Node<E> left, Node<E> right, int size) {
            this.key = key;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = size;
        }
    }

    private static final class Del<E> {
        final Node<E> node;
        final boolean shortage; // subtree black height is one less than expected
        E removed;

        Del(Node<E> node, boolean shortage) {
            this.node = node;
            this.shortage = shortage;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order must not be null");
        }
        this.order = order;
    }

    // ---------------------------------------------------------------- public API

    public boolean add(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (containsLocked(item)) {
                return false;
            }
            Node<T> inserted = ins(root, item);
            root = blacken(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        checkNotNull(item);
        synchronized (lock) {
            if (!containsLocked(item)) {
                return false;
            }
            Del<T> d = del(root, item);
            Node<T> r = d.node;
            if (r != null && r.red) {
                r = blacken(r);
            }
            root = r;
            return true;
        }
    }

    public boolean contains(T item) {
        checkNotNull(item);
        synchronized (lock) {
            return containsLocked(item);
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            int n = sizeOf(root);
            if (index < 0 || index >= n) {
                throw new IndexOutOfBoundsException("index " + index + " out of range for size " + n);
            }
            return nthOf(root, index);
        }
    }

    public int rank(T item) {
        checkNotNull(item);
        synchronized (lock) {
            int r = 0;
            Node<T> n = root;
            while (n != null) {
                int c = order.compare(item, n.key);
                if (c > 0) {
                    r += sizeOf(n.left) + 1;
                    n = n.right;
                } else {
                    n = n.left;
                }
            }
            return r;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(root, order);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void checkNotNull(Object o) {
        if (o == null) {
            throw new NullPointerException("argument must not be null");
        }
    }

    static <E> int sizeOf(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    static <E> E nthOf(Node<E> n, int index) {
        Node<E> cur = n;
        int idx = index;
        while (true) {
            int ls = sizeOf(cur.left);
            if (idx < ls) {
                cur = cur.left;
            } else if (idx == ls) {
                return cur.key;
            } else {
                idx -= ls + 1;
                cur = cur.right;
            }
        }
    }

    private static <E> boolean isRed(Node<E> n) {
        return n != null && n.red;
    }

    private static <E> Node<E> mk(boolean red, Node<E> l, E key, Node<E> r) {
        return new Node<>(key, red, l, r, sizeOf(l) + sizeOf(r) + 1);
    }

    private static <E> Node<E> blacken(Node<E> n) {
        return mk(false, n.left, n.key, n.right);
    }

    private static <E> Node<E> redden(Node<E> n) {
        return mk(true, n.left, n.key, n.right);
    }

    private boolean containsLocked(T item) {
        Node<T> n = root;
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c == 0) {
                return true;
            }
            n = c < 0 ? n.left : n.right;
        }
        return false;
    }

    // ---------------------------------------------------------------- insertion

    private Node<T> ins(Node<T> t, T item) {
        if (t == null) {
            return new Node<>(item, true, null, null, 1);
        }
        int c = order.compare(item, t.key);
        if (c < 0) {
            return balance(t.red, ins(t.left, item), t.key, t.right);
        } else {
            return balance(t.red, t.left, t.key, ins(t.right, item));
        }
    }

    private static <E> Node<E> balance(boolean red, Node<E> l, E k, Node<E> r) {
        if (!red) {
            if (isRed(l) && isRed(l.left)) {
                return mk(true, mk(false, l.left.left, l.left.key, l.left.right), l.key, mk(false, l.right, k, r));
            }
            if (isRed(l) && isRed(l.right)) {
                return mk(true, mk(false, l.left, l.key, l.right.left), l.right.key, mk(false, l.right.right, k, r));
            }
            if (isRed(r) && isRed(r.left)) {
                return mk(true, mk(false, l, k, r.left.left), r.left.key, mk(false, r.left.right, r.key, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                return mk(true, mk(false, l, k, r.left), r.key, mk(false, r.right.left, r.right.key, r.right.right));
            }
        }
        return mk(red, l, k, r);
    }

    // ---------------------------------------------------------------- deletion

    // Precondition: item is present in the subtree rooted at t.
    private Del<T> del(Node<T> t, T item) {
        int c = order.compare(item, t.key);
        if (c < 0) {
            Del<T> d = del(t.left, item);
            return fixLeft(t.red, t.key, d.node, t.right, d.shortage);
        }
        if (c > 0) {
            Del<T> d = del(t.right, item);
            return fixRight(t.red, t.key, t.left, d.node, d.shortage);
        }
        // Found the node to delete.
        if (t.left == null) {
            if (t.right == null) {
                return new Del<>(null, !t.red);
            }
            return new Del<>(blacken(t.right), false);
        }
        if (t.right == null) {
            return new Del<>(blacken(t.left), false);
        }
        Del<T> m = removeMin(t.right);
        return fixRight(t.red, m.removed, t.left, m.node, m.shortage);
    }

    // Removes the minimum of a non-null subtree; the removed key is stored in result.removed.
    private static <E> Del<E> removeMin(Node<E> t) {
        if (t.left == null) {
            Del<E> d;
            if (t.right == null) {
                d = new Del<>(null, !t.red);
            } else {
                d = new Del<>(blacken(t.right), false);
            }
            d.removed = t.key;
            return d;
        }
        Del<E> d = removeMin(t.left);
        Del<E> res = fixLeft(t.red, t.key, d.node, t.right, d.shortage);
        res.removed = d.removed;
        return res;
    }

    // Left subtree 'l' may be one black short; right subtree 'r' is the sibling.
    private static <E> Del<E> fixLeft(boolean red, E key, Node<E> l, Node<E> r, boolean short_) {
        if (!short_) {
            return new Del<>(mk(red, l, key, r), false);
        }
        // r is non-null here.
        if (!r.red) {
            if (isRed(r.right)) {
                return new Del<>(mk(red, mk(false, l, key, r.left), r.key, blacken(r.right)), false);
            }
            if (isRed(r.left)) {
                Node<E> rl = r.left;
                return new Del<>(mk(red, mk(false, l, key, rl.left), rl.key, mk(false, rl.right, r.key, r.right)), false);
            }
            return new Del<>(mk(false, l, key, redden(r)), !red);
        }
        Del<E> d = fixLeft(true, key, l, r.left, true);
        return new Del<>(mk(false, d.node, r.key, r.right), d.shortage);
    }

    // Right subtree 'r' may be one black short; left subtree 'l' is the sibling.
    private static <E> Del<E> fixRight(boolean red, E key, Node<E> l, Node<E> r, boolean short_) {
        if (!short_) {
            return new Del<>(mk(red, l, key, r), false);
        }
        // l is non-null here.
        if (!l.red) {
            if (isRed(l.left)) {
                return new Del<>(mk(red, blacken(l.left), l.key, mk(false, l.right, key, r)), false);
            }
            if (isRed(l.right)) {
                Node<E> lr = l.right;
                return new Del<>(mk(red, mk(false, l.left, l.key, lr.left), lr.key, mk(false, lr.right, key, r)), false);
            }
            return new Del<>(mk(false, redden(l), key, r), !red);
        }
        Del<E> d = fixRight(true, key, l.right, r, true);
        return new Del<>(mk(false, l.left, l.key, d.node), d.shortage);
    }
}
