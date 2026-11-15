interface Order<T> {
    int compare(T left, T right);
}

interface Snapshot<T> {
    int size();

    T get(int index);

    boolean contains(T item);
}

/*
 * Concurrency and failure policy:
 * - All public operations of TreeSet and all operations of its snapshots that touch
 *   shared state are linearized by a single private monitor (synchronized blocks only).
 * - The set is unbounded, so no operation waits for capacity. Operations never block
 *   except while acquiring the monitor, so no wait/notify protocol is required.
 * - An empty set is not an error: size() returns 0, remove/contains/add/rank behave
 *   normally, and get(index) throws IndexOutOfBoundsException for any index outside
 *   [0, size).
 * - Null arguments throw NullPointerException before any mutation occurs.
 * - Nodes of the red-black tree are immutable. Every update copies the path it touches
 *   (path copying), so a snapshot that holds a root reference observes a frozen tree
 *   and never races with later writes. Final fields give safe publication of nodes.
 *
 * The tree is a left-leaning red-black tree (LLRB) implemented persistently:
 * black root, no red node with a red child, equal black height on all paths, and
 * subtree element counts maintained in every node.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}


    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<E> {
        final E key;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int size;

        Node(E key, boolean red, Node<E> left, Node<E> right) {
            this.key = key;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + TreeSet.sizeOf(left) + TreeSet.sizeOf(right);
        }
    }

    private static final class SnapshotImpl<E> implements Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;

        SnapshotImpl(Node<E> root, Order<E> order) {
            this.root = root;
            this.order = order;
        }

        @Override
        public int size() {
            return TreeSet.sizeOf(root);
        }

        @Override
        public E get(int index) {
            if (index < 0 || index >= TreeSet.sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return TreeSet.getAt(root, index);
        }

        @Override
        public boolean contains(E item) {
            TreeSet.requireNonNull(item);
            return TreeSet.findNode(root, order, item) != null;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeSet(Order<T> order) {
        requireNonNull(order);
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (findNode(root, order, item) != null) {
                return false;
            }
            root = recolor(insert(root, item), BLACK);
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (findNode(root, order, item) == null) {
                return false;
            }
            Node<T> r = root;
            if (!isRed(r.left) && !isRed(r.right)) {
                r = recolor(r, RED);
            }
            Node<T> nr = delete(r, item);
            if (nr != null) {
                nr = recolor(nr, BLACK);
            }
            root = nr;
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return findNode(root, order, item) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return getAt(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return rankOf(root, order, item);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<>(root, order);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void requireNonNull(Object o) {
        if (o == null) {
            throw new NullPointerException();
        }
    }

    private static <E> int sizeOf(Node<E> n) {
        return n == null ? 0 : n.size;
    }

    private static <E> boolean isRed(Node<E> n) {
        return n != null && n.red;
    }

    private static <E> Node<E> recolor(Node<E> n, boolean red) {
        return new Node<>(n.key, red, n.left, n.right);
    }

    private static <E> Node<E> findNode(Node<E> n, Order<E> order, E item) {
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c == 0) {
                return n;
            } else if (c < 0) {
                n = n.left;
            } else {
                n = n.right;
            }
        }
        return null;
    }

    private static <E> int rankOf(Node<E> n, Order<E> order, E item) {
        int r = 0;
        while (n != null) {
            if (order.compare(item, n.key) <= 0) {
                n = n.left;
            } else {
                r += sizeOf(n.left) + 1;
                n = n.right;
            }
        }
        return r;
    }

    private static <E> E getAt(Node<E> n, int index) {
        while (n != null) {
            int ls = sizeOf(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return n.key;
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <E> Node<E> minNode(Node<E> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    // ------------------------------------------------- persistent LLRB rotations

    private static <E> Node<E> rotateLeft(Node<E> h) {
        Node<E> x = h.right;
        return new Node<>(x.key, h.red,
                new Node<>(h.key, RED, h.left, x.left),
                x.right);
    }

    private static <E> Node<E> rotateRight(Node<E> h) {
        Node<E> x = h.left;
        return new Node<>(x.key, h.red,
                x.left,
                new Node<>(h.key, RED, x.right, h.right));
    }

    private static <E> Node<E> flipColors(Node<E> h) {
        Node<E> l = h.left;
        Node<E> r = h.right;
        return new Node<>(h.key, !h.red,
                new Node<>(l.key, !l.red, l.left, l.right),
                new Node<>(r.key, !r.red, r.left, r.right));
    }

    /** Local repair after a child changed; restores LLRB local invariants. */
    private static <E> Node<E> fixUp(Node<E> n) {
        if (isRed(n.right) && !isRed(n.left)) {
            n = rotateLeft(n);
        }
        if (isRed(n.left) && isRed(n.left.left)) {
            n = rotateRight(n);
        }
        if (isRed(n.left) && isRed(n.right)) {
            n = flipColors(n);
        }
        return n;
    }

    private static <E> Node<E> moveRedLeft(Node<E> h) {
        h = flipColors(h);
        if (isRed(h.right.left)) {
            h = new Node<>(h.key, h.red, h.left, rotateRight(h.right));
            h = rotateLeft(h);
            h = flipColors(h);
        }
        return h;
    }

    private static <E> Node<E> moveRedRight(Node<E> h) {
        h = flipColors(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
            h = flipColors(h);
        }
        return h;
    }

    // ------------------------------------------------------- insert and delete

    private Node<T> insert(Node<T> h, T item) {
        if (h == null) {
            return new Node<>(item, RED, null, null);
        }
        Node<T> n;
        if (order.compare(item, h.key) < 0) {
            n = new Node<>(h.key, h.red, insert(h.left, item), h.right);
        } else {
            n = new Node<>(h.key, h.red, h.left, insert(h.right, item));
        }
        return fixUp(n);
    }

    /** Precondition: key is present in the subtree rooted at h. */
    private Node<T> delete(Node<T> h, T key) {
        if (order.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) {
                h = moveRedLeft(h);
            }
            h = new Node<>(h.key, h.red, delete(h.left, key), h.right);
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }
            if (order.compare(key, h.key) == 0 && h.right == null) {
                return null;
            }
            if (!isRed(h.right) && !isRed(h.right.left)) {
                h = moveRedRight(h);
            }
            if (order.compare(key, h.key) == 0) {
                Node<T> m = minNode(h.right);
                h = new Node<>(m.key, h.red, h.left, deleteMin(h.right));
            } else {
                h = new Node<>(h.key, h.red, h.left, delete(h.right, key));
            }
        }
        return fixUp(h);
    }

    private static <E> Node<E> deleteMin(Node<E> h) {
        if (h.left == null) {
            return null;
        }
        if (!isRed(h.left) && !isRed(h.left.left)) {
            h = moveRedLeft(h);
        }
        h = new Node<>(h.key, h.red, deleteMin(h.left), h.right);
        return fixUp(h);
    }
}
