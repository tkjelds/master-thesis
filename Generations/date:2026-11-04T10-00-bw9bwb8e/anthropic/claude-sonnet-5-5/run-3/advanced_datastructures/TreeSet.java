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


    // Immutable persistent node. Never modified after construction.
    private static final class Node<T> {
        final boolean red;
        final Node<T> left;
        final T item;
        final Node<T> right;
        final int size;

        Node(boolean red, Node<T> left, T item, Node<T> right) {
            this.red = red;
            this.left = left;
            this.item = item;
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
            return nth(root, index);
        }

        public boolean contains(T item) {
            if (item == null) throw new NullPointerException("item");
            return find(root, item, order);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private volatile Node<T> root; // immutable tree; replaced under lock

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException("order");
        this.order = order;
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

    private static <T> Node<T> red(Node<T> l, T x, Node<T> r) {
        return new Node<T>(true, l, x, r);
    }

    private static <T> Node<T> black(Node<T> l, T x, Node<T> r) {
        return new Node<T>(false, l, x, r);
    }

    private static <T> boolean find(Node<T> n, T item, Order<T> order) {
        while (n != null) {
            int c = order.compare(item, n.item);
            if (c == 0) return true;
            n = c < 0 ? n.left : n.right;
        }
        return false;
    }

    private static <T> T nth(Node<T> n, int index) {
        if (index < 0 || index >= sz(n)) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + sz(n));
        }
        while (true) {
            int ls = sz(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return n.item;
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
    }

    // Okasaki-style balance for a black node
    private static <T> Node<T> balance(Node<T> l, T x, Node<T> r) {
        if (isRed(l)) {
            if (isRed(l.left)) {
                Node<T> ll = l.left;
                return red(black(ll.left, ll.item, ll.right), l.item, black(l.right, x, r));
            }
            if (isRed(l.right)) {
                Node<T> lr = l.right;
                return red(black(l.left, l.item, lr.left), lr.item, black(lr.right, x, r));
            }
        }
        if (isRed(r)) {
            if (isRed(r.left)) {
                Node<T> rl = r.left;
                return red(black(l, x, rl.left), rl.item, black(rl.right, r.item, r.right));
            }
            if (isRed(r.right)) {
                Node<T> rr = r.right;
                return red(black(l, x, r.left), r.item, black(rr.left, rr.item, rr.right));
            }
        }
        return black(l, x, r);
    }

    private static <T> Node<T> sub1(Node<T> n) {
        // n must be black
        return red(n.left, n.item, n.right);
    }

    private static <T> Node<T> balleft(Node<T> bl, T x, Node<T> r) {
        if (isRed(bl)) {
            return red(black(bl.left, bl.item, bl.right), x, r);
        }
        if (isBlack(r)) {
            return balance(bl, x, red(r.left, r.item, r.right));
        }
        // r is red with black left child
        Node<T> rl = r.left;
        return red(black(bl, x, rl.left), rl.item,
                balance(rl.right, r.item, sub1(r.right)));
    }

    private static <T> Node<T> balright(Node<T> l, T x, Node<T> br) {
        if (isRed(br)) {
            return red(l, x, black(br.left, br.item, br.right));
        }
        if (isBlack(l)) {
            return balance(red(l.left, l.item, l.right), x, br);
        }
        // l is red with black right child
        Node<T> lr = l.right;
        return red(balance(sub1(l.left), l.item, lr.left), lr.item,
                black(lr.right, x, br));
    }

    private static <T> Node<T> app(Node<T> a, Node<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.red && b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return red(red(a.left, a.item, bc.left), bc.item,
                        red(bc.right, b.item, b.right));
            }
            return red(a.left, a.item, red(bc, b.item, b.right));
        }
        if (!a.red && !b.red) {
            Node<T> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return red(black(a.left, a.item, bc.left), bc.item,
                        black(bc.right, b.item, b.right));
            }
            return balleft(a.left, a.item, black(bc, b.item, b.right));
        }
        if (b.red) {
            return red(app(a, b.left), b.item, b.right);
        }
        return red(a.left, a.item, app(a.right, b));
    }

    private Node<T> ins(Node<T> n, T x) {
        if (n == null) return red(null, x, null);
        int c = order.compare(x, n.item);
        if (c < 0) {
            Node<T> nl = ins(n.left, x);
            return n.red ? red(nl, n.item, n.right) : balance(nl, n.item, n.right);
        } else {
            Node<T> nr = ins(n.right, x);
            return n.red ? red(n.left, n.item, nr) : balance(n.left, n.item, nr);
        }
    }

    // Precondition: x is present in n.
    private Node<T> del(Node<T> n, T x) {
        int c = order.compare(x, n.item);
        if (c < 0) {
            Node<T> nl = del(n.left, x);
            if (isBlack(n.left)) return balleft(nl, n.item, n.right);
            return red(nl, n.item, n.right);
        } else if (c > 0) {
            Node<T> nr = del(n.right, x);
            if (isBlack(n.right)) return balright(n.left, n.item, nr);
            return red(n.left, n.item, nr);
        } else {
            return app(n.left, n.right);
        }
    }

    private static <T> Node<T> blacken(Node<T> n) {
        if (n == null || !n.red) return n;
        return black(n.left, n.item, n.right);
    }

    // ---------- public API ----------
    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (find(r, item, order)) return false;
            Node<T> nr = blacken(ins(r, item));
            root = nr;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (!find(r, item, order)) return false;
            root = blacken(del(r, item));
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        return find(root, item, order);
    }

    public int size() {
        return sz(root);
    }

    public T get(int index) {
        return nth(root, index);
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException("item");
        Node<T> n = root;
        int count = 0;
        while (n != null) {
            int c = order.compare(item, n.item);
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
        return new Snap<T>(root, order);
    }
}
