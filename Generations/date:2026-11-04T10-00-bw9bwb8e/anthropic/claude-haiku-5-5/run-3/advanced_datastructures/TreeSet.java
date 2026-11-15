interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final TreeSet.Node<T> root;

    Snapshot(Order<T> order, TreeSet.Node<T> root) {
        this.order = order;
        this.root = root;
    }

    public int size() {
        return TreeSet.count(root);
    }

    public T get(int index) {
        return TreeSet.nthElem(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        return TreeSet.findNode(order, root, item) != null;
    }
}

/*
 * Concurrency policy:
 * - All live operations run under one private monitor (lock). Operations never block
 *   on capacity: the set is unbounded. get(index) on an out-of-range index throws
 *   IndexOutOfBoundsException immediately (including on an empty set) instead of waiting.
 * - Nodes are immutable. Updates copy only the root-to-target path (path copying) and
 *   publish a new root under the lock. Snapshots keep the old root, so they never
 *   observe later writes and need no locking themselves. Final fields of nodes give
 *   safe publication to snapshot holders.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}


    static final class Node<E> {
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final E elem;
        final int size;

        Node(boolean red, Node<E> left, E elem, Node<E> right) {
            this.red = red;
            this.left = left;
            this.right = right;
            this.elem = elem;
            this.size = count(left) + count(right) + 1;
        }
    }

    private static final class Res<E> {
        final Node<E> node;
        final boolean shortened; // black height of node is one less than expected

        Res(Node<E> node, boolean shortened) {
            this.node = node;
            this.shortened = shortened;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        checkNonNull(item);
        synchronized (lock) {
            if (findNode(order, root, item) != null) {
                return false;
            }
            root = blacken(insert(root, item));
            return true;
        }
    }

    public boolean remove(T item) {
        checkNonNull(item);
        synchronized (lock) {
            if (findNode(order, root, item) == null) {
                return false;
            }
            Res<T> r = delete(root, item);
            root = blacken(r.node);
            return true;
        }
    }

    public boolean contains(T item) {
        checkNonNull(item);
        synchronized (lock) {
            return findNode(order, root, item) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return nthElem(root, index);
        }
    }

    public int rank(T item) {
        checkNonNull(item);
        synchronized (lock) {
            return rankOf(order, root, item);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<>(order, root);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void checkNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException("element");
        }
    }

    static <E> int count(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    static <E> Node<E> findNode(Order<E> order, Node<E> n, E item) {
        while (n != null) {
            int c = order.compare(item, n.elem);
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

    static <E> E nthElem(Node<E> n, int index) {
        int total = count(n);
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + total);
        }
        Node<E> cur = n;
        int i = index;
        while (true) {
            int ls = count(cur.left);
            if (i < ls) {
                cur = cur.left;
            } else if (i == ls) {
                return cur.elem;
            } else {
                i -= ls + 1;
                cur = cur.right;
            }
        }
    }

    static <E> int rankOf(Order<E> order, Node<E> n, E item) {
        int r = 0;
        while (n != null) {
            if (order.compare(item, n.elem) <= 0) {
                n = n.left;
            } else {
                r += count(n.left) + 1;
                n = n.right;
            }
        }
        return r;
    }

    private static <E> Node<E> blacken(Node<E> n) {
        if (n == null || !n.red) {
            return n;
        }
        return new Node<>(false, n.left, n.elem, n.right);
    }

    // ------------------------------------------------------------- insertion

    private Node<T> insert(Node<T> n, T x) {
        if (n == null) {
            return new Node<>(true, null, x, null);
        }
        int c = order.compare(x, n.elem);
        if (c < 0) {
            return balance(n.red, insert(n.left, x), n.elem, n.right);
        } else if (c > 0) {
            return balance(n.red, n.left, n.elem, insert(n.right, x));
        }
        return n; // not reached: caller checks for existence first
    }

    // Okasaki-style balancing; only black nodes can repair a red-red violation.
    private static <E> Node<E> balance(boolean red, Node<E> l, E e, Node<E> r) {
        if (red) {
            return new Node<>(true, l, e, r);
        }
        if (l != null && l.red) {
            if (l.left != null && l.left.red) {
                Node<E> a = l.left;
                return new Node<>(true,
                        new Node<>(false, a.left, a.elem, a.right),
                        l.elem,
                        new Node<>(false, l.right, e, r));
            }
            if (l.right != null && l.right.red) {
                Node<E> b = l.right;
                return new Node<>(true,
                        new Node<>(false, l.left, l.elem, b.left),
                        b.elem,
                        new Node<>(false, b.right, e, r));
            }
        }
        if (r != null && r.red) {
            if (r.left != null && r.left.red) {
                Node<E> b = r.left;
                return new Node<>(true,
                        new Node<>(false, l, e, b.left),
                        b.elem,
                        new Node<>(false, b.right, r.elem, r.right));
            }
            if (r.right != null && r.right.red) {
                Node<E> c = r.right;
                return new Node<>(true,
                        new Node<>(false, l, e, r.left),
                        r.elem,
                        new Node<>(false, c.left, c.elem, c.right));
            }
        }
        return new Node<>(false, l, e, r);
    }

    // ------------------------------------------------------------- deletion

    // Precondition: the element exists in the subtree n.
    private Res<T> delete(Node<T> n, T x) {
        int c = order.compare(x, n.elem);
        if (c < 0) {
            Res<T> r = delete(n.left, x);
            if (!r.shortened) {
                return new Res<>(new Node<>(n.red, r.node, n.elem, n.right), false);
            }
            return fixLeft(n.red, r.node, n.elem, n.right);
        } else if (c > 0) {
            Res<T> r = delete(n.right, x);
            if (!r.shortened) {
                return new Res<>(new Node<>(n.red, n.left, n.elem, r.node), false);
            }
            return fixRight(n.red, n.left, n.elem, r.node);
        }
        return removeNode(n);
    }

    // Removes the node n itself, returning the replacement subtree.
    private static <E> Res<E> removeNode(Node<E> n) {
        if (n.left == null) {
            if (n.right == null) {
                return new Res<>(null, !n.red);
            }
            return new Res<>(blacken(n.right), false);
        }
        if (n.right == null) {
            return new Res<>(blacken(n.left), false);
        }
        E m = minElem(n.right);
        Res<E> r = deleteMin(n.right);
        if (!r.shortened) {
            return new Res<>(new Node<>(n.red, n.left, m, r.node), false);
        }
        return fixRight(n.red, n.left, m, r.node);
    }

    private static <E> Res<E> deleteMin(Node<E> n) {
        if (n.left == null) {
            return removeNode(n);
        }
        Res<E> r = deleteMin(n.left);
        if (!r.shortened) {
            return new Res<>(new Node<>(n.red, r.node, n.elem, n.right), false);
        }
        return fixLeft(n.red, r.node, n.elem, n.right);
    }

    private static <E> E minElem(Node<E> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n.elem;
    }

    // l: left subtree whose black height dropped by one; r: sibling (non-null).
    private static <E> Res<E> fixLeft(boolean red, Node<E> l, E e, Node<E> r) {
        if (r == null) {
            throw new IllegalStateException("red-black invariant violated");
        }
        if (r.red) {
            // Sibling red: rotate, then repair the red parent beneath the new root.
            Res<E> inner = fixLeft(true, l, e, r.left);
            return new Res<>(new Node<>(false, inner.node, r.elem, r.right), false);
        }
        if (r.left != null && r.left.red) {
            Node<E> s = r.left;
            Node<E> left = new Node<>(false, l, e, s.left);
            Node<E> right = new Node<>(false, s.right, r.elem, r.right);
            return new Res<>(new Node<>(red, left, s.elem, right), false);
        }
        if (r.right != null && r.right.red) {
            Node<E> left = new Node<>(false, l, e, r.left);
            return new Res<>(new Node<>(red, left, r.elem, blacken(r.right)), false);
        }
        Node<E> newR = new Node<>(true, r.left, r.elem, r.right);
        return new Res<>(new Node<>(false, l, e, newR), !red);
    }

    // r: right subtree whose black height dropped by one; l: sibling (non-null).
    private static <E> Res<E> fixRight(boolean red, Node<E> l, E e, Node<E> r) {
        if (l == null) {
            throw new IllegalStateException("red-black invariant violated");
        }
        if (l.red) {
            Res<E> inner = fixRight(true, l.right, e, r);
            return new Res<>(new Node<>(false, l.left, l.elem, inner.node), false);
        }
        if (l.right != null && l.right.red) {
            Node<E> s = l.right;
            Node<E> left = new Node<>(false, l.left, l.elem, s.left);
            Node<E> right = new Node<>(false, s.right, e, r);
            return new Res<>(new Node<>(red, left, s.elem, right), false);
        }
        if (l.left != null && l.left.red) {
            Node<E> right = new Node<>(false, l.right, e, r);
            return new Res<>(new Node<>(red, blacken(l.left), l.elem, right), false);
        }
        Node<E> newL = new Node<>(true, l.left, l.elem, l.right);
        return new Res<>(new Node<>(false, newL, e, r), !red);
    }
}
