import java.lang.*;

interface Order<T> {
    int compare(T left, T right);
}

interface Snapshot<T> {
    int size();
    T get(int index);
    boolean contains(T item);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final T val;
        final int size;

        Node(boolean red, Node<T> left, T val, Node<T> right) {
            this.red = red;
            this.left = left;
            this.val = val;
            this.right = right;
            this.size = 1 + sz(left) + sz(right);
        }
    }

    private static final class Snap<T> implements Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        Snap(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return sz(root);
        }

        public T get(int index) {
            return select(root, index);
        }

        public boolean contains(T item) {
            if (item == null) throw new NullPointerException("item");
            return find(root, item, order);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private volatile Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException("order");
        this.order = order;
        this.root = null;
    }

    // ---------- helpers ----------
    private static <T> int sz(Node<T> n) {
        return n == null ? 0 : n.size;
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private static <T> boolean isBlack(Node<T> n) {
        return n != null && !n.red;
    }

    private static <T> Node<T> R(Node<T> l, T v, Node<T> r) {
        return new Node<T>(true, l, v, r);
    }

    private static <T> Node<T> B(Node<T> l, T v, Node<T> r) {
        return new Node<T>(false, l, v, r);
    }

    private static <T> Node<T> blackOf(Node<T> n) {
        return B(n.left, n.val, n.right);
    }

    private static <T> Node<T> redOf(Node<T> n) {
        return R(n.left, n.val, n.right);
    }

    private static <T> boolean find(Node<T> n, T item, Order<T> order) {
        while (n != null) {
            int c = order.compare(item, n.val);
            if (c == 0) return true;
            n = c < 0 ? n.left : n.right;
        }
        return false;
    }

    private static <T> T select(Node<T> n, int index) {
        if (index < 0 || index >= sz(n)) throw new IndexOutOfBoundsException("index " + index);
        while (true) {
            int ls = sz(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return n.val;
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
    }

    // ---------- balancing ----------
    private Node<T> balance(Node<T> a, T x, Node<T> b) {
        if (isRed(a) && isRed(b)) {
            return R(blackOf(a), x, blackOf(b));
        }
        if (isRed(a) && isRed(a.left)) {
            Node<T> l = a.left;
            return R(blackOf(l), a.val, B(a.right, x, b));
        }
        if (isRed(a) && isRed(a.right)) {
            Node<T> m = a.right;
            return R(B(a.left, a.val, m.left), m.val, B(m.right, x, b));
        }
        if (isRed(b) && isRed(b.right)) {
            return R(B(a, x, b.left), b.val, blackOf(b.right));
        }
        if (isRed(b) && isRed(b.left)) {
            Node<T> m = b.left;
            return R(B(a, x, m.left), m.val, B(m.right, b.val, b.right));
        }
        return B(a, x, b);
    }

    private Node<T> balleft(Node<T> l, T x, Node<T> r) {
        if (isRed(l)) {
            return R(blackOf(l), x, r);
        }
        if (isBlack(r)) {
            return balance(l, x, redOf(r));
        }
        if (isRed(r) && isBlack(r.left)) {
            Node<T> m = r.left;
            return R(B(l, x, m.left), m.val, balance(m.right, r.val, redOf(r.right)));
        }
        throw new IllegalStateException("red-black invariant violated");
    }

    private Node<T> balright(Node<T> l, T x, Node<T> r) {
        if (isRed(r)) {
            return R(l, x, blackOf(r));
        }
        if (isBlack(l)) {
            return balance(redOf(l), x, r);
        }
        if (isRed(l) && isBlack(l.right)) {
            Node<T> m = l.right;
            return R(balance(redOf(l.left), l.val, m.left), m.val, B(m.right, x, r));
        }
        throw new IllegalStateException("red-black invariant violated");
    }

    private Node<T> app(Node<T> a, Node<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.red && b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return R(R(a.left, a.val, bc.left), bc.val, R(bc.right, b.val, b.right));
            }
            return R(a.left, a.val, R(bc, b.val, b.right));
        }
        if (!a.red && !b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return R(B(a.left, a.val, bc.left), bc.val, B(bc.right, b.val, b.right));
            }
            return balleft(a.left, a.val, B(bc, b.val, b.right));
        }
        if (b.red) {
            return R(app(a, b.left), b.val, b.right);
        }
        // a is red, b is black
        return R(a.left, a.val, app(a.right, b));
    }

    // ---------- insert / delete ----------
    private Node<T> ins(Node<T> n, T item) {
        if (n == null) return R(null, item, null);
        int c = order.compare(item, n.val);
        if (c < 0) {
            Node<T> nl = ins(n.left, item);
            return n.red ? R(nl, n.val, n.right) : balance(nl, n.val, n.right);
        } else if (c > 0) {
            Node<T> nr = ins(n.right, item);
            return n.red ? R(n.left, n.val, nr) : balance(n.left, n.val, nr);
        }
        return n; // not reached when absent is verified
    }

    private Node<T> del(Node<T> n, T item) {
        if (n == null) return null;
        int c = order.compare(item, n.val);
        if (c < 0) {
            Node<T> nl = del(n.left, item);
            if (isBlack(n.left)) return balleft(nl, n.val, n.right);
            return R(nl, n.val, n.right);
        } else if (c > 0) {
            Node<T> nr = del(n.right, item);
            if (isBlack(n.right)) return balright(n.left, n.val, nr);
            return R(n.left, n.val, nr);
        }
        return app(n.left, n.right);
    }

    // ---------- public API ----------
    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (find(r, item, order)) return false;
            Node<T> nr = ins(r, item);
            if (nr.red) nr = blackOf(nr);
            root = nr;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (!find(r, item, order)) return false;
            Node<T> nr = del(r, item);
            if (nr != null && nr.red) nr = blackOf(nr);
            root = nr;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        Node<T> r = root;
        return find(r, item, order);
    }

    public int size() {
        return sz(root);
    }

    public T get(int index) {
        Node<T> r = root;
        return select(r, index);
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException("item");
        Node<T> n = root;
        int count = 0;
        while (n != null) {
            int c = order.compare(item, n.val);
            if (c <= 0) {
                n = n.left;
            } else {
                count += sz(n.left) + 1;
                n = n.right;
            }
        }
        return count;
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snap<T>(root, order);
        }
    }
}
