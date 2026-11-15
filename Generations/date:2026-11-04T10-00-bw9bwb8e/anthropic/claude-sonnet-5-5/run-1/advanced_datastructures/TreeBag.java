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


    private static final class Node<T> {
        final T item;
        final int count;
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final int total;
        final int distinct;

        Node(T item, int count, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.total = count + tot(left) + tot(right);
            this.distinct = 1 + dist(left) + dist(right);
        }
    }

    private static <T> int tot(Node<T> n) {
        return n == null ? 0 : n.total;
    }

    private static <T> int dist(Node<T> n) {
        return n == null ? 0 : n.distinct;
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private static <T> boolean isBlack(Node<T> n) {
        return n != null && !n.red;
    }

    private static <T> Node<T> mk(boolean red, Node<T> l, Node<T> src, Node<T> r) {
        return new Node<T>(src.item, src.count, red, l, r);
    }

    private static <T> Node<T> blacken(Node<T> n) {
        if (n == null || !n.red) return n;
        return mk(false, n.left, n, n.right);
    }

    private static <T> Node<T> reden(Node<T> n) {
        return mk(true, n.left, n, n.right);
    }

    private static <T> Node<T> find(Node<T> n, T item, Order<T> order) {
        while (n != null) {
            int c = order.compare(item, n.item);
            if (c == 0) return n;
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    private static <T> int countIn(Node<T> root, T item, Order<T> order) {
        Node<T> n = find(root, item, order);
        return n == null ? 0 : n.count;
    }

    private static <T> T select(Node<T> root, int index) {
        if (index < 0 || index >= tot(root)) {
            throw new IndexOutOfBoundsException("index " + index);
        }
        Node<T> n = root;
        while (true) {
            int ls = tot(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index < ls + n.count) {
                return n.item;
            } else {
                index -= ls + n.count;
                n = n.right;
            }
        }
    }

    // Okasaki balance for a black node with middle source x.
    private static <T> Node<T> balance(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(l) && isRed(l.left)) {
            Node<T> ll = l.left;
            return mk(true, mk(false, ll.left, ll, ll.right), l,
                    mk(false, l.right, x, r));
        }
        if (isRed(l) && isRed(l.right)) {
            Node<T> lr = l.right;
            return mk(true, mk(false, l.left, l, lr.left), lr,
                    mk(false, lr.right, x, r));
        }
        if (isRed(r) && isRed(r.left)) {
            Node<T> rl = r.left;
            return mk(true, mk(false, l, x, rl.left), rl,
                    mk(false, rl.right, r, r.right));
        }
        if (isRed(r) && isRed(r.right)) {
            Node<T> rr = r.right;
            return mk(true, mk(false, l, x, r.left), r,
                    mk(false, rr.left, rr, rr.right));
        }
        return mk(false, l, x, r);
    }

    private static <T> Node<T> balleft(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(l)) {
            return mk(true, blacken(l), x, r);
        }
        if (isBlack(r)) {
            return balance(l, x, reden(r));
        }
        // r is red with black left child
        Node<T> rl = r.left;
        Node<T> c = r.right;
        Node<T> sub = reden(c);
        return mk(true, mk(false, l, x, rl.left), rl,
                balance(rl.right, r, sub));
    }

    private static <T> Node<T> balright(Node<T> l, Node<T> x, Node<T> r) {
        if (isRed(r)) {
            return mk(true, l, x, blacken(r));
        }
        if (isBlack(l)) {
            return balance(reden(l), x, r);
        }
        // l is red with black right child
        Node<T> a = l.left;
        Node<T> m = l.right;
        return mk(true, balance(reden(a), l, m.left), m,
                mk(false, m.right, x, r));
    }

    private static <T> Node<T> app(Node<T> a, Node<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.red && b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return mk(true, mk(true, a.left, a, bc.left), bc,
                        mk(true, bc.right, b, b.right));
            }
            return mk(true, a.left, a, mk(true, bc, b, b.right));
        }
        if (!a.red && !b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return mk(true, mk(false, a.left, a, bc.left), bc,
                        mk(false, bc.right, b, b.right));
            }
            return balleft(a.left, a, mk(false, bc, b, b.right));
        }
        if (b.red) {
            return mk(true, app(a, b.left), b, b.right);
        }
        return mk(true, a.left, a, app(a.right, b));
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;

    public TreeBag(Order<T> order) {
        this.order = Objects.requireNonNull(order);
    }

    private Node<T> ins(Node<T> t, T item) {
        if (t == null) return new Node<T>(item, 1, true, null, null);
        int c = order.compare(item, t.item);
        if (c < 0) {
            Node<T> nl = ins(t.left, item);
            return t.red ? mk(true, nl, t, t.right) : balance(nl, t, t.right);
        } else {
            Node<T> nr = ins(t.right, item);
            return t.red ? mk(true, t.left, t, nr) : balance(t.left, t, nr);
        }
    }

    private Node<T> adjust(Node<T> t, T item, int delta) {
        int c = order.compare(item, t.item);
        if (c == 0) {
            return new Node<T>(t.item, t.count + delta, t.red, t.left, t.right);
        }
        if (c < 0) {
            return new Node<T>(t.item, t.count, t.red, adjust(t.left, item, delta), t.right);
        }
        return new Node<T>(t.item, t.count, t.red, t.left, adjust(t.right, item, delta));
    }

    private Node<T> del(Node<T> t, T item) {
        if (t == null) return null;
        int c = order.compare(item, t.item);
        if (c < 0) {
            if (isBlack(t.left)) {
                return balleft(del(t.left, item), t, t.right);
            }
            return mk(true, del(t.left, item), t, t.right);
        }
        if (c > 0) {
            if (isBlack(t.right)) {
                return balright(t.left, t, del(t.right, item));
            }
            return mk(true, t.left, t, del(t.right, item));
        }
        return app(t.left, t.right);
    }

    public void add(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            if (tot(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("size overflow");
            }
            if (find(root, item, order) != null) {
                root = adjust(root, item, 1);
            } else {
                root = blacken(ins(root, item));
            }
        }
    }

    public boolean remove(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            Node<T> n = find(root, item, order);
            if (n == null) return false;
            if (n.count > 1) {
                root = adjust(root, item, -1);
            } else {
                root = blacken(del(root, item));
            }
            return true;
        }
    }

    public int count(T item) {
        Objects.requireNonNull(item);
        synchronized (lock) {
            return countIn(root, item, order);
        }
    }

    public int size() {
        synchronized (lock) {
            return tot(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return dist(root);
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
            Node<T> n = root;
            while (n != null) {
                int c = order.compare(item, n.item);
                if (c <= 0) {
                    n = n.left;
                } else {
                    r += tot(n.left) + n.count;
                    n = n.right;
                }
            }
            return r;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<T>(root, order);
        }
    }

    private static final class SnapshotImpl<T> implements Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        SnapshotImpl(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return tot(root);
        }

        public int distinctSize() {
            return dist(root);
        }

        public int count(T item) {
            Objects.requireNonNull(item);
            return countIn(root, item, order);
        }

        public T get(int index) {
            return select(root, index);
        }
    }
}
