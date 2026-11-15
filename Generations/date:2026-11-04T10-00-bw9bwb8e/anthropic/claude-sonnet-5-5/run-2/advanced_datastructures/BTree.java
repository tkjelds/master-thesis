import java.lang.reflect.Array;

interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}


    private static final class Node {
        final Object[] keys;
        final Node[] children; // null for leaves
        int n;
        final boolean leaf;

        Node(int t, boolean leaf) {
            this.leaf = leaf;
            this.keys = new Object[2 * t - 1];
            this.children = leaf ? null : new Node[2 * t];
        }
    }

    private final int t;
    private final Order<T> order;
    private final Object lock = new Object();
    private Node root; // null when empty
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("invalid minimumDegree");
        }
        this.t = minimumDegree;
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private int cmp(Object a, Object b) {
        return order.compare((T) a, (T) b);
    }

    // first index i in node with keys[i] >= k
    private int lowerBound(Node x, Object k) {
        int lo = 0, hi = x.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cmp(x.keys[mid], k) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private boolean containsInternal(Object k) {
        Node x = root;
        while (x != null) {
            int i = lowerBound(x, k);
            if (i < x.n && cmp(x.keys[i], k) == 0) {
                return true;
            }
            if (x.leaf) {
                return false;
            }
            x = x.children[i];
        }
        return false;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            return containsInternal(item);
        }
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (root == null) {
                Node r = new Node(t, true);
                r.keys[0] = item;
                r.n = 1;
                root = r;
                size = 1;
                return true;
            }
            if (containsInternal(item)) {
                return false;
            }
            if (root.n == 2 * t - 1) {
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

    private void splitChild(Node parent, int i) {
        Node y = parent.children[i];
        Node z = new Node(t, y.leaf);
        z.n = t - 1;
        for (int j = 0; j < t - 1; j++) {
            z.keys[j] = y.keys[t + j];
            y.keys[t + j] = null;
        }
        if (!y.leaf) {
            for (int j = 0; j < t; j++) {
                z.children[j] = y.children[t + j];
                y.children[t + j] = null;
            }
        }
        Object median = y.keys[t - 1];
        y.keys[t - 1] = null;
        y.n = t - 1;
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
            int i = lowerBound(x, k);
            if (x.leaf) {
                for (int j = x.n; j > i; j--) {
                    x.keys[j] = x.keys[j - 1];
                }
                x.keys[i] = k;
                x.n++;
                return;
            }
            if (x.children[i].n == 2 * t - 1) {
                splitChild(x, i);
                if (cmp(k, x.keys[i]) > 0) {
                    i++;
                }
            }
            x = x.children[i];
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (root == null || !containsInternal(item)) {
                return false;
            }
            delete(root, item);
            size--;
            if (root.n == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    private void delete(Node x, Object k) {
        while (true) {
            int idx = lowerBound(x, k);
            boolean found = idx < x.n && cmp(x.keys[idx], k) == 0;
            if (x.leaf) {
                // found is guaranteed
                for (int j = idx; j < x.n - 1; j++) {
                    x.keys[j] = x.keys[j + 1];
                }
                x.keys[x.n - 1] = null;
                x.n--;
                return;
            }
            if (found) {
                if (x.children[idx].n >= t) {
                    Node c = x.children[idx];
                    while (!c.leaf) {
                        c = c.children[c.n];
                    }
                    Object pred = c.keys[c.n - 1];
                    x.keys[idx] = pred;
                    x = x.children[idx];
                    k = pred;
                } else if (x.children[idx + 1].n >= t) {
                    Node c = x.children[idx + 1];
                    while (!c.leaf) {
                        c = c.children[0];
                    }
                    Object succ = c.keys[0];
                    x.keys[idx] = succ;
                    x = x.children[idx + 1];
                    k = succ;
                } else {
                    merge(x, idx);
                    x = x.children[idx];
                }
            } else {
                if (x.children[idx].n == t - 1) {
                    if (idx > 0 && x.children[idx - 1].n >= t) {
                        borrowFromPrev(x, idx);
                    } else if (idx < x.n && x.children[idx + 1].n >= t) {
                        borrowFromNext(x, idx);
                    } else if (idx < x.n) {
                        merge(x, idx);
                    } else {
                        merge(x, idx - 1);
                        idx--;
                    }
                }
                x = x.children[idx];
            }
        }
    }

    private void merge(Node x, int i) {
        Node c = x.children[i];
        Node s = x.children[i + 1];
        c.keys[t - 1] = x.keys[i];
        for (int j = 0; j < s.n; j++) {
            c.keys[t + j] = s.keys[j];
        }
        if (!c.leaf) {
            for (int j = 0; j <= s.n; j++) {
                c.children[t + j] = s.children[j];
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

    private void borrowFromPrev(Node x, int idx) {
        Node c = x.children[idx];
        Node s = x.children[idx - 1];
        for (int j = c.n; j > 0; j--) {
            c.keys[j] = c.keys[j - 1];
        }
        if (!c.leaf) {
            for (int j = c.n + 1; j > 0; j--) {
                c.children[j] = c.children[j - 1];
            }
            c.children[0] = s.children[s.n];
            s.children[s.n] = null;
        }
        c.keys[0] = x.keys[idx - 1];
        x.keys[idx - 1] = s.keys[s.n - 1];
        s.keys[s.n - 1] = null;
        s.n--;
        c.n++;
    }

    private void borrowFromNext(Node x, int idx) {
        Node c = x.children[idx];
        Node s = x.children[idx + 1];
        c.keys[c.n] = x.keys[idx];
        if (!c.leaf) {
            c.children[c.n + 1] = s.children[0];
        }
        x.keys[idx] = s.keys[0];
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

    @SuppressWarnings("unchecked")
    public T first() {
        synchronized (lock) {
            if (size == 0) {
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
            if (size == 0) {
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
}
