interface Order<K> {
    int compare(K left, K right);
}

interface Entry<K, V> {
    K key();
    V value();
}

interface Snapshot<K, V> {
    int size();
    V get(K key);
    Entry<K, V> entryAt(int index);
}

@TreeDictionary.ThreadSafe
public class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    private static final class Node<K, V> {
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final K key;
        final V value;
        final int size;

        Node(boolean red, Node<K, V> left, K key, V value, Node<K, V> right) {
            this.red = red;
            this.left = left;
            this.right = right;
            this.key = key;
            this.value = value;
            this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
        }
    }

    private static final class SimpleEntry<K, V> implements Entry<K, V> {
        private final K key;
        private final V value;

        SimpleEntry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K key() {
            return key;
        }

        public V value() {
            return value;
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    private static final class SnapshotImpl<K, V> implements Snapshot<K, V> {
        private final Node<K, V> root;
        private final Order<K> order;

        SnapshotImpl(Node<K, V> root, Order<K> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return root == null ? 0 : root.size;
        }

        public V get(K key) {
            if (key == null) throw new NullPointerException("key");
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c == 0) return n.value;
                n = c < 0 ? n.left : n.right;
            }
            return null;
        }

        public Entry<K, V> entryAt(int index) {
            return TreeDictionary.entryAtRoot(root, index);
        }
    }

    private final Order<K> order;
    private final Object lock = new Object();
    private Node<K, V> root; // guarded by lock

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException("order");
        this.order = order;
    }

    // ---------- public API ----------

    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        synchronized (lock) {
            Node<K, V> existing = find(root, key);
            V old = existing == null ? null : existing.value;
            Node<K, V> nr = insert(root, key, value);
            root = blacken(nr);
            return old;
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        Node<K, V> r;
        synchronized (lock) {
            r = root;
        }
        Node<K, V> n = find(r, key);
        return n == null ? null : n.value;
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            Node<K, V> existing = find(root, key);
            if (existing == null) return null;
            V old = existing.value;
            Node<K, V> nr = del(root, key);
            root = blacken(nr);
            return old;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException("key");
        Node<K, V> r;
        synchronized (lock) {
            r = root;
        }
        return find(r, key) != null;
    }

    public int size() {
        synchronized (lock) {
            return root == null ? 0 : root.size;
        }
    }

    public Entry<K, V> entryAt(int index) {
        Node<K, V> r;
        synchronized (lock) {
            r = root;
        }
        return entryAtRoot(r, index);
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException("key");
        Node<K, V> r;
        synchronized (lock) {
            r = root;
        }
        int count = 0;
        Node<K, V> n = r;
        while (n != null) {
            if (order.compare(key, n.key) <= 0) {
                n = n.left;
            } else {
                count += 1 + sz(n.left);
                n = n.right;
            }
        }
        return count;
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<K, V>(root, order);
        }
    }

    // ---------- helpers ----------

    private static <K, V> Entry<K, V> entryAtRoot(Node<K, V> r, int index) {
        int total = r == null ? 0 : r.size;
        if (index < 0 || index >= total) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + total);
        }
        Node<K, V> n = r;
        int i = index;
        while (true) {
            int ls = n.left == null ? 0 : n.left.size;
            if (i < ls) {
                n = n.left;
            } else if (i == ls) {
                return new SimpleEntry<K, V>(n.key, n.value);
            } else {
                i -= ls + 1;
                n = n.right;
            }
        }
    }

    private static <K, V> int sz(Node<K, V> n) {
        return n == null ? 0 : n.size;
    }

    private Node<K, V> find(Node<K, V> n, K key) {
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c == 0) return n;
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    private static <K, V> boolean isRed(Node<K, V> n) {
        return n != null && n.red;
    }

    private static <K, V> boolean isBlack(Node<K, V> n) {
        return n != null && !n.red;
    }

    private Node<K, V> mk(boolean red, Node<K, V> l, Node<K, V> src, Node<K, V> r) {
        return new Node<K, V>(red, l, src.key, src.value, r);
    }

    private Node<K, V> blacken(Node<K, V> n) {
        if (n == null || !n.red) return n;
        return mk(false, n.left, n, n.right);
    }

    private Node<K, V> redden(Node<K, V> n) {
        return mk(true, n.left, n, n.right);
    }

    // Black node with Okasaki rebalancing.
    private Node<K, V> balance(Node<K, V> l, Node<K, V> s, Node<K, V> r) {
        if (isRed(l) && isRed(l.left)) {
            Node<K, V> x = l.left;
            return mk(true, mk(false, x.left, x, x.right), l, mk(false, l.right, s, r));
        }
        if (isRed(l) && isRed(l.right)) {
            Node<K, V> m = l.right;
            return mk(true, mk(false, l.left, l, m.left), m, mk(false, m.right, s, r));
        }
        if (isRed(r) && isRed(r.left)) {
            Node<K, V> m = r.left;
            return mk(true, mk(false, l, s, m.left), m, mk(false, m.right, r, r.right));
        }
        if (isRed(r) && isRed(r.right)) {
            Node<K, V> m = r.right;
            return mk(true, mk(false, l, s, r.left), r, mk(false, m.left, m, m.right));
        }
        return mk(false, l, s, r);
    }

    private Node<K, V> insert(Node<K, V> t, K key, V value) {
        if (t == null) return new Node<K, V>(true, null, key, value, null);
        int c = order.compare(key, t.key);
        if (c < 0) {
            Node<K, V> nl = insert(t.left, key, value);
            return t.red ? mk(true, nl, t, t.right) : balance(nl, t, t.right);
        } else if (c > 0) {
            Node<K, V> nr = insert(t.right, key, value);
            return t.red ? mk(true, t.left, t, nr) : balance(t.left, t, nr);
        } else {
            return new Node<K, V>(t.red, t.left, t.key, value, t.right);
        }
    }

    // Persistent deletion (after Kahrs). Key is known to be present.
    private Node<K, V> del(Node<K, V> t, K key) {
        if (t == null) return null;
        int c = order.compare(key, t.key);
        if (c < 0) {
            Node<K, V> nl = del(t.left, key);
            if (isBlack(t.left)) return balleft(nl, t, t.right);
            return mk(true, nl, t, t.right);
        } else if (c > 0) {
            Node<K, V> nr = del(t.right, key);
            if (isBlack(t.right)) return balright(t.left, t, nr);
            return mk(true, t.left, t, nr);
        } else {
            return app(t.left, t.right);
        }
    }

    private Node<K, V> balleft(Node<K, V> l, Node<K, V> s, Node<K, V> r) {
        if (isRed(l)) {
            return mk(true, blacken(l), s, r);
        }
        if (isBlack(r)) {
            return balance(l, s, redden(r));
        }
        if (isRed(r) && isBlack(r.left)) {
            Node<K, V> y = r.left;
            Node<K, V> c = r.right;
            return mk(true, mk(false, l, s, y.left), y,
                    balance(y.right, r, redden(c)));
        }
        throw new IllegalStateException("red-black invariant violated");
    }

    private Node<K, V> balright(Node<K, V> l, Node<K, V> s, Node<K, V> r) {
        if (isRed(r)) {
            return mk(true, l, s, blacken(r));
        }
        if (isBlack(l)) {
            return balance(redden(l), s, r);
        }
        if (isRed(l) && isBlack(l.right)) {
            Node<K, V> m = l.right;
            Node<K, V> a = l.left;
            return mk(true, balance(redden(a), l, m.left), m,
                    mk(false, m.right, s, r));
        }
        throw new IllegalStateException("red-black invariant violated");
    }

    private Node<K, V> app(Node<K, V> a, Node<K, V> b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.red && b.red) {
            Node<K, V> bc = app(a.right, b.left);
            if (isRed(bc)) {
                return mk(true, mk(true, a.left, a, bc.left), bc,
                        mk(true, bc.right, b, b.right));
            }
            return mk(true, a.left, a, mk(true, bc, b, b.right));
        }
        if (!a.red && !b.red) {
            Node<K, V> bc = app(a.right, b.left);
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
}
