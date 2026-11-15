interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}


    private static final class Node {
        int n;
        final boolean leaf;
        final Object[] keys;
        final Node[] children;

        Node(int t, boolean leaf) {
            this.leaf = leaf;
            this.keys = new Object[2 * t - 1];
            this.children = leaf ? null : new Node[2 * t];
        }
    }

    private final int t;
    private final int maxKeys;
    private final Order<T> order;
    private final Object lock = new Object();
    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        if (minimumDegree < 2 || 2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid minimumDegree");
        }
        this.t = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private int cmp(Object a, Object b) {
        return order.compare((T) a, (T) b);
    }

    // ---------- public API ----------

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (root == null) {
                root = new Node(t, true);
                root.keys[0] = item;
                root.n = 1;
                size++;
                return true;
            }
            if (find(item)) {
                return false;
            }
            if (root.n == maxKeys) {
                Node s = new Node(t, false);
                s.children[0] = root;
                splitChild(s, 0);
                root = s;
            }
            insertNonFull(root, item);
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (root == null || !find(item)) {
                return false;
            }
            delete(root, item);
            if (root.n == 0) {
                if (root.leaf) {
                    root = null;
                } else {
                    root = root.children[0];
                }
            }
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return root != null && find(item);
        }
    }

    @SuppressWarnings("unchecked")
    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node x = root;
            while (!x.leaf) {
                x = x.children[0];
            }
            return (T) x.keys[0];
        }
    }

    @SuppressWarnings("unchecked")
    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty");
            }
            Node x = root;
            while (!x.leaf) {
                x = x.children[x.n];
            }
            return (T) x.keys[x.n - 1];
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            if (root != null) {
                fill(root, out, 0);
            }
            return out;
        }
    }

    // ---------- internals (call with lock held) ----------

    private int fill(Node x, Object[] out, int pos) {
        for (int i = 0; i < x.n; i++) {
            if (!x.leaf) {
                pos = fill(x.children[i], out, pos);
            }
            out[pos++] = x.keys[i];
        }
        if (!x.leaf) {
            pos = fill(x.children[x.n], out, pos);
        }
        return pos;
    }

    private boolean find(Object k) {
        Node x = root;
        while (x != null) {
            int lo = 0, hi = x.n;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                int c = cmp(k, x.keys[mid]);
                if (c == 0) {
                    return true;
                }
                if (c < 0) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            if (x.leaf) {
                return false;
            }
            x = x.children[lo];
        }
        return false;
    }

    // first index i with keys[i] >= k
    private int lowerBound(Node x, Object k) {
        int lo = 0, hi = x.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cmp(k, x.keys[mid]) <= 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private void splitChild(Node parent, int i) {
        Node y = parent.children[i];
        Node z = new Node(t, y.leaf);
        for (int j = 0; j < t - 1; j++) {
            z.keys[j] = y.keys[j + t];
            y.keys[j + t] = null;
        }
        if (!y.leaf) {
            for (int j = 0; j < t; j++) {
                z.children[j] = y.children[j + t];
                y.children[j + t] = null;
            }
        }
        Object median = y.keys[t - 1];
        y.keys[t - 1] = null;
        y.n = t - 1;
        z.n = t - 1;
        for (int j = parent.n; j > i; j--) {
            parent.keys[j] = parent.keys[j - 1];
        }
        for (int j = parent.n + 1; j > i + 1; j--) {
            parent.children[j] = parent.children[j - 1];
        }
        parent.keys[i] = median;
        parent.children[i + 1] = z;
        parent.n++;
    }

    private void insertNonFull(Node x, Object k) {
        while (true) {
            if (x.leaf) {
                int i = x.n - 1;
                while (i >= 0 && cmp(k, x.keys[i]) < 0) {
                    x.keys[i + 1] = x.keys[i];
                    i--;
                }
                x.keys[i + 1] = k;
                x.n++;
                return;
            }
            int i = lowerBound(x, k);
            if (x.children[i].n == maxKeys) {
                splitChild(x, i);
                if (cmp(k, x.keys[i]) > 0) {
                    i++;
                }
            }
            x = x.children[i];
        }
    }

    private void delete(Node x, Object k) {
        int i = lowerBound(x, k);
        boolean found = i < x.n && cmp(k, x.keys[i]) == 0;
        if (x.leaf) {
            if (found) {
                for (int j = i; j < x.n - 1; j++) {
                    x.keys[j] = x.keys[j + 1];
                }
                x.keys[x.n - 1] = null;
                x.n--;
            }
            return;
        }
        if (found) {
            Node left = x.children[i];
            Node right = x.children[i + 1];
            if (left.n >= t) {
                Node m = left;
                while (!m.leaf) {
                    m = m.children[m.n];
                }
                Object pred = m.keys[m.n - 1];
                x.keys[i] = pred;
                delete(left, pred);
            } else if (right.n >= t) {
                Node m = right;
                while (!m.leaf) {
                    m = m.children[0];
                }
                Object succ = m.keys[0];
                x.keys[i] = succ;
                delete(right, succ);
            } else {
                merge(x, i);
                delete(x.children[i], k);
            }
            return;
        }
        if (x.children[i].n == t - 1) {
            if (i > 0 && x.children[i - 1].n >= t) {
                borrowFromPrev(x, i);
            } else if (i < x.n && x.children[i + 1].n >= t) {
                borrowFromNext(x, i);
            } else if (i < x.n) {
                merge(x, i);
            } else {
                merge(x, i - 1);
                i--;
            }
        }
        delete(x.children[i], k);
    }

    // merges children[i], keys[i], children[i+1] into children[i]
    private void merge(Node x, int i) {
        Node c = x.children[i];
        Node s = x.children[i + 1];
        c.keys[c.n] = x.keys[i];
        for (int j = 0; j < s.n; j++) {
            c.keys[c.n + 1 + j] = s.keys[j];
        }
        if (!c.leaf) {
            for (int j = 0; j <= s.n; j++) {
                c.children[c.n + 1 + j] = s.children[j];
            }
        }
        c.n = c.n + 1 + s.n;
        for (int j = i; j < x.n - 1; j++) {
            x.keys[j] = x.keys[j + 1];
        }
        x.keys[x.n - 1] = null;
        for (int j = i + 1; j < x.n; j++) {
            x.children[j] = x.children[j + 1];
        }
        x.children[x.n] = null;
        x.n--;
    }

    private void borrowFromPrev(Node x, int i) {
        Node c = x.children[i];
        Node s = x.children[i - 1];
        for (int j = c.n - 1; j >= 0; j--) {
            c.keys[j + 1] = c.keys[j];
        }
        if (!c.leaf) {
            for (int j = c.n; j >= 0; j--) {
                c.children[j + 1] = c.children[j];
            }
            c.children[0] = s.children[s.n];
            s.children[s.n] = null;
        }
        c.keys[0] = x.keys[i - 1];
        x.keys[i - 1] = s.keys[s.n - 1];
        s.keys[s.n - 1] = null;
        s.n--;
        c.n++;
    }

    private void borrowFromNext(Node x, int i) {
        Node c = x.children[i];
        Node s = x.children[i + 1];
        c.keys[c.n] = x.keys[i];
        if (!c.leaf) {
            c.children[c.n + 1] = s.children[0];
        }
        x.keys[i] = s.keys[0];
        for (int j = 0; j < s.n - 1; j++) {
            s.keys[j] = s.keys[j + 1];
        }
        s.keys[s.n - 1] = null;
        if (!s.leaf) {
            for (int j = 0; j < s.n; j++) {
                s.children[j] = s.children[j + 1];
            }
            s.children[s.n] = null;
        }
        s.n--;
        c.n++;
    }
}
