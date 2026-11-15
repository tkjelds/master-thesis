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


    // Immutable persistent node: path copying gives O(1) snapshots.
    private static final class Node<T> {
        final T item;
        final int count;
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final int total;
        final int distinct;

        Node(boolean red, Node<T> left, T item, int count, Node<T> right) {
            this.red = red;
            this.left = left;
            this.item = item;
            this.count = count;
            this.right = right;
            this.total = (left == null ? 0 : left.total) + (right == null ? 0 : right.total) + count;
            this.distinct = (left == null ? 0 : left.distinct) + (right == null ? 0 : right.distinct) + 1;
        }
    }

    // Stateless (apart from the order) algorithms over immutable nodes.
    private static final class Ops<T> {
        final Order<T> order;

        Ops(Order<T> order) {
            this.order = order;
        }

        boolean isRed(Node<T> n) {
            return n != null && n.red;
        }

        boolean isBlack(Node<T> n) {
            return n != null && !n.red;
        }

        Node<T> mk(boolean red, Node<T> l, Node<T> src, Node<T> r) {
            return new Node<T>(red, l, src.item, src.count, r);
        }

        Node<T> sub1(Node<T> n) {
            return mk(true, n.left, n, n.right);
        }

        Node<T> find(Node<T> t, T item) {
            while (t != null) {
                int c = order.compare(item, t.item);
                if (c == 0) return t;
                t = c < 0 ? t.left : t.right;
            }
            return null;
        }

        int countOf(Node<T> root, T item) {
            Node<T> n = find(root, item);
            return n == null ? 0 : n.count;
        }

        T select(Node<T> root, int index) {
            if (root == null || index < 0 || index >= root.total) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            Node<T> t = root;
            while (true) {
                int lt = t.left == null ? 0 : t.left.total;
                if (index < lt) {
                    t = t.left;
                } else if (index < lt + t.count) {
                    return t.item;
                } else {
                    index -= lt + t.count;
                    t = t.right;
                }
            }
        }

        int rank(Node<T> root, T item) {
            int r = 0;
            Node<T> t = root;
            while (t != null) {
                int c = order.compare(item, t.item);
                if (c <= 0) {
                    t = t.left;
                } else {
                    r += (t.left == null ? 0 : t.left.total) + t.count;
                    t = t.right;
                }
            }
            return r;
        }

        Node<T> setCount(Node<T> t, T item, int newCount) {
            int c = order.compare(item, t.item);
            if (c == 0) return new Node<T>(t.red, t.left, t.item, newCount, t.right);
            if (c < 0) return new Node<T>(t.red, setCount(t.left, item, newCount), t.item, t.count, t.right);
            return new Node<T>(t.red, t.left, t.item, t.count, setCount(t.right, item, newCount));
        }

        // Balance for a black node m with children l, r.
        Node<T> balance(Node<T> l, Node<T> m, Node<T> r) {
            if (isRed(l) && isRed(l.left)) {
                Node<T> x = l.left;
                return mk(true, mk(false, x.left, x, x.right), l, mk(false, l.right, m, r));
            }
            if (isRed(l) && isRed(l.right)) {
                Node<T> y = l.right;
                return mk(true, mk(false, l.left, l, y.left), y, mk(false, y.right, m, r));
            }
            if (isRed(r) && isRed(r.left)) {
                Node<T> y = r.left;
                return mk(true, mk(false, l, m, y.left), y, mk(false, y.right, r, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                Node<T> z = r.right;
                return mk(true, mk(false, l, m, r.left), r, mk(false, z.left, z, z.right));
            }
            return mk(false, l, m, r);
        }

        Node<T> insert(Node<T> root, T item) {
            Node<T> r = ins(root, item);
            return r.red ? mk(false, r.left, r, r.right) : r;
        }

        private Node<T> ins(Node<T> t, T item) {
            if (t == null) return new Node<T>(true, null, item, 1, null);
            int c = order.compare(item, t.item);
            if (c < 0) {
                Node<T> nl = ins(t.left, item);
                return t.red ? mk(true, nl, t, t.right) : balance(nl, t, t.right);
            } else {
                Node<T> nr = ins(t.right, item);
                return t.red ? mk(true, t.left, t, nr) : balance(t.left, t, nr);
            }
        }

        Node<T> balleft(Node<T> l, Node<T> x, Node<T> r) {
            if (isRed(l)) {
                return mk(true, mk(false, l.left, l, l.right), x, r);
            }
            if (isBlack(r)) {
                return balance(l, x, mk(true, r.left, r, r.right));
            }
            // r is red with black left child
            Node<T> rl = r.left;
            return mk(true, mk(false, l, x, rl.left), rl, balance(rl.right, r, sub1(r.right)));
        }

        Node<T> balright(Node<T> l, Node<T> x, Node<T> r) {
            if (isRed(r)) {
                return mk(true, l, x, mk(false, r.left, r, r.right));
            }
            if (isBlack(l)) {
                return balance(mk(true, l.left, l, l.right), x, r);
            }
            // l is red with black right child
            Node<T> lr = l.right;
            return mk(true, balance(sub1(l.left), l, lr.left), lr, mk(false, lr.right, x, r));
        }

        Node<T> app(Node<T> a, Node<T> b) {
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

        // Removes the node for item entirely; item must be present.
        Node<T> delete(Node<T> root, T item) {
            Node<T> r = del(root, item);
            return (r != null && r.red) ? mk(false, r.left, r, r.right) : r;
        }

        private Node<T> del(Node<T> t, T item) {
            int c = order.compare(item, t.item);
            if (c < 0) {
                Node<T> nl = del(t.left, item);
                if (isBlack(t.left)) return balleft(nl, t, t.right);
                return mk(true, nl, t, t.right);
            } else if (c > 0) {
                Node<T> nr = del(t.right, item);
                if (isBlack(t.right)) return balright(t.left, t, nr);
                return mk(true, t.left, t, nr);
            } else {
                return app(t.left, t.right);
            }
        }
    }

    private static final class SnapshotImpl<T> implements Snapshot<T> {
        private final Ops<T> ops;
        private final Node<T> root;

        SnapshotImpl(Ops<T> ops, Node<T> root) {
            this.ops = ops;
            this.root = root;
        }

        public int size() {
            return root == null ? 0 : root.total;
        }

        public int distinctSize() {
            return root == null ? 0 : root.distinct;
        }

        public int count(T item) {
            if (item == null) throw new NullPointerException("item");
            return ops.countOf(root, item);
        }

        public T get(int index) {
            return ops.select(root, index);
        }
    }

    private final Object lock = new Object();
    private final Ops<T> ops;
    private Node<T> root; // guarded by lock

    public TreeBag(Order<T> order) {
        if (order == null) throw new NullPointerException("order");
        this.ops = new Ops<T>(order);
    }

    public void add(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            if (root != null && root.total == Integer.MAX_VALUE) {
                throw new IllegalStateException("size overflow");
            }
            Node<T> found = ops.find(root, item);
            if (found != null) {
                root = ops.setCount(root, item, found.count + 1);
            } else {
                root = ops.insert(root, item);
            }
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> found = ops.find(root, item);
            if (found == null) return false;
            if (found.count > 1) {
                root = ops.setCount(root, item, found.count - 1);
            } else {
                root = ops.delete(root, item);
            }
            return true;
        }
    }

    public int count(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            return ops.countOf(root, item);
        }
    }

    public int size() {
        synchronized (lock) {
            return root == null ? 0 : root.total;
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return root == null ? 0 : root.distinct;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return ops.select(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            return ops.rank(root, item);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<T>(ops, root);
        }
    }
}
