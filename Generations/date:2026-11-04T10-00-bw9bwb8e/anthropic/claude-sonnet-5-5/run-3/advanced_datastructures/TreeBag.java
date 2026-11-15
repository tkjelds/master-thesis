import java.util.Objects;

interface Order<T> {
    int compare(T left, T right);
}

interface Snapshot<T> {
    int size();
    int distinctSize();
    int count(T item);
    T get(int index);
}

@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}


    // Immutable persistent node; shared between versions and snapshots.
    private static final class Node<T> {
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final T item;
        final int count;
        final int total;
        final int distinct;

        Node(boolean red, Node<T> left, T item, int count, Node<T> right) {
            this.red = red;
            this.left = left;
            this.right = right;
            this.item = item;
            this.count = count;
            this.total = totalOf(left) + totalOf(right) + count;
            this.distinct = distinctOf(left) + distinctOf(right) + 1;
        }
    }

    private static <T> int totalOf(Node<T> n) {
        return n == null ? 0 : n.total;
    }

    private static <T> int distinctOf(Node<T> n) {
        return n == null ? 0 : n.distinct;
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private static <T> boolean isBlack(Node<T> n) {
        return n != null && !n.red;
    }

    // New node using src's item and count.
    private static <T> Node<T> mk(boolean red, Node<T> l, Node<T> src, Node<T> r) {
        return new Node<T>(red, l, src.item, src.count, r);
    }

    private static <T> Node<T> findNode(Node<T> root, Order<T> order, T item) {
        Node<T> t = root;
        while (t != null) {
            int c = order.compare(item, t.item);
            if (c == 0) return t;
            t = c < 0 ? t.left : t.right;
        }
        return null;
    }

    private static <T> int countIn(Node<T> root, Order<T> order, T item) {
        Node<T> n = findNode(root, order, item);
        return n == null ? 0 : n.count;
    }

    private static <T> T select(Node<T> root, int index) {
        if (index < 0 || index >= totalOf(root)) {
            throw new IndexOutOfBoundsException("Index: " + index + ", size: " + totalOf(root));
        }
        Node<T> t = root;
        int i = index;
        while (true) {
            int lt = totalOf(t.left);
            if (i < lt) {
                t = t.left;
            } else {
                i -= lt;
                if (i < t.count) return t.item;
                i -= t.count;
                t = t.right;
            }
        }
    }

    // Okasaki-style balance for a black node whose key/count come from x.
    private static <T> Node<T> balance(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(l)) {
            if (isRed(l.left)) {
                Node<T> a = l.left;
                return mk(true, mk(false, a.left, a, a.right), l, mk(false, l.right, x, r));
            }
            if (isRed(l.right)) {
                Node<T> y = l.right;
                return mk(true, mk(false, l.left, l, y.left), y, mk(false, y.right, x, r));
            }
        }
        if (isRed(r)) {
            if (isRed(r.left)) {
                Node<T> y = r.left;
                return mk(true, mk(false, l, x, y.left), y, mk(false, y.right, r, r.right));
            }
            if (isRed(r.right)) {
                Node<T> z = r.right;
                return mk(true, mk(false, l, x, r.left), r, mk(false, z.left, z, z.right));
            }
        }
        return mk(false, l, x, r);
    }

    private static <T> Node<T> sub1(Node<T> n) {
        return mk(true, n.left, n, n.right);
    }

    private static <T> Node<T> balleft(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(l)) {
            return mk(true, mk(false, l.left, l, l.right), x, r);
        }
        if (isBlack(r)) {
            return balance(l, x, mk(true, r.left, r, r.right));
        }
        // r is red with black left child
        Node<T> y = r.left;
        return mk(true, mk(false, l, x, y.left), y, balance(y.right, r, sub1(r.right)));
    }

    private static <T> Node<T> balright(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(r)) {
            return mk(true, l, x, mk(false, r.left, r, r.right));
        }
        if (isBlack(l)) {
            return balance(mk(true, l.left, l, l.right), x, r);
        }
        // l is red with black right child
        Node<T> y = l.right;
        return mk(true, balance(sub1(l.left), l, y.left), y, mk(false, y.right, x, r));
    }

    private static <T> Node<T> app(Node<T> a, Node<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.red && b.red) {
            Node<T> m = app(a.right, b.left);
            if (isRed(m)) {
                return mk(true, mk(true, a.left, a, m.left), m, mk(true, m.right, b, b.right));
            }
            return mk(true, a.left, a, mk(true, m, b, b.right));
        }
        if (!a.red && !b.red) {
            Node<T> m = app(a.right, b.left);
            if (isRed(m)) {
                return mk(true, mk(false, a.left, a, m.left), m, mk(false, m.right, b, b.right));
            }
            return balleft(a.left, a, mk(false, m, b, b.right));
        }
        if (b.red) {
            return mk(true, app(a, b.left), b, b.right);
        }
        return mk(true, a.left, a, app(a.right, b));
    }

    private static final class Snap<T> implements Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        Snap(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return totalOf(root);
        }

        public int distinctSize() {
            return distinctOf(root);
        }

        public int count(T item) {
            Objects.requireNonNull(item);
            return countIn(root, order, item);
        }

        public T get(int index) {
            return select(root, index);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root; // guarded by lock

    public TreeBag(Order<T> order) {
        this.order = Objects.requireNonNull(order);
    }

    private Node<T> ins(Node<T> t, T item) {
        if (t == null) return new Node<T>(true, null, item, 1, null);
        int c = order.compare(item, t.item);
        if (c < 0) {
            Node<T> nl = ins(t.left, item);
            return t.red ? mk(true, nl, t, t.right) : balance(nl, t, t.right);
        } else if (c > 0) {
            Node<T> nr = ins(t.right, item);
            return t.red ? mk(true, t.left, t, nr) : balance(t.left, t, nr);
        }
        return new Node<T>(t.red, t.left, t.item, t.count + 1, t.right);
    }

    private Node<T> dec(Node<T> t, T item) {
        int c = order.compare(item, t.item);
        if (c < 0) return mk(t.red, dec(t.left, item), t, t.right);
        if (c > 0) return mk(t.red, t.left, t, dec(t.right, item));
        return new Node<T>(t.red, t.left, t.item, t.count - 1, t.right);
    }

    private Node<T> del(Node<T> t, T item) {
        int c = order.compare(item, t.item);
        if (c < 0) {
            if (isBlack(t.left)) return balleft(del(t.left, item), t, t.right);
            return mk(true, del(t.left, item), t, t.right);
        } else if (c > 0) {
            if (isBlack(t.right)) return balright(t.left, t, del(t.right, item));
            return mk(true, t.left, t, del(t.right, item));
        }
        return app(t.left, t.right);
    }

    private static <T> Node<T> blacken(Node<T> n) {
        if (n == null || !n.red) return n;
        return mk(false, n.left, n, n.right);
    }

    public void add(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            if (totalOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("size overflow");
            }
            root = blacken(ins(root, item));
        }
    }

    public boolean remove(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            Node<T> n = findNode(root, order, item);
            if (n == null) return false;
            if (n.count > 1) {
                root = dec(root, item);
            } else {
                root = blacken(del(root, item));
            }
            return true;
        }
    }

    public int count(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            return countIn(root, order, item);
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
            return select(root, index);
        }
    }

    public int rank(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            int r = 0;
            Node<T> t = root;
            while (t != null) {
                int c = order.compare(item, t.item);
                if (c <= 0) {
                    t = t.left;
                } else {
                    r += totalOf(t.left) + t.count;
                    t = t.right;
                }
            }
            return r;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snap<T>(root, order);
        }
    }
}
