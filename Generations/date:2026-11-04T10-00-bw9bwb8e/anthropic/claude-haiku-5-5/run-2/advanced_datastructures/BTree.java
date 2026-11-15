interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        final Object[] keys;
        final Node<T>[] kids;
        final boolean leaf;
        int n;

        @SuppressWarnings("unchecked")
        Node(int t, boolean leaf) {
            this.keys = new Object[2 * t - 1];
            this.kids = (Node<T>[]) new Node[2 * t];
            this.leaf = leaf;
            this.n = 0;
        }
    }

    private final int t;
    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int count;

    public BTree(int minimumDegree, Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if (2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("minimumDegree node capacity overflows int");
        }
        this.t = minimumDegree;
        this.order = order;
        this.root = null;
        this.count = 0;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (containsLocked(item)) {
                return false;
            }
            if (root == null) {
                root = new Node<T>(t, true);
            } else if (root.n == 2 * t - 1) {
                Node<T> s = new Node<T>(t, false);
                s.kids[0] = root;
                splitChild(s, 0);
                root = s;
            }
            insertNonFull(root, item);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (!containsLocked(item)) {
                return false;
            }
            removeLocked(item);
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return containsLocked(item);
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return cast(minKey(root));
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return cast(maxKey(root));
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[count];
            fill(root, out, 0);
            return out;
        }
    }

    // ---- internal helpers (must be called while holding lock) ----

    @SuppressWarnings("unchecked")
    private T cast(Object o) {
        return (T) o;
    }

    @SuppressWarnings("unchecked")
    private int cmp(Object a, Object b) {
        return order.compare((T) a, (T) b);
    }

    private int lowerBound(Node<T> x, Object k) {
        int lo = 0;
        int hi = x.n;
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

    private boolean containsLocked(Object item) {
        Node<T> x = root;
        while (x != null) {
            int i = lowerBound(x, item);
            if (i < x.n && cmp(x.keys[i], item) == 0) {
                return true;
            }
            if (x.leaf) {
                return false;
            }
            x = x.kids[i];
        }
        return false;
    }

    private Object minKey(Node<T> x) {
        while (!x.leaf) {
            x = x.kids[0];
        }
        return x.keys[0];
    }

    private Object maxKey(Node<T> x) {
        while (!x.leaf) {
            x = x.kids[x.n];
        }
        return x.keys[x.n - 1];
    }

    private int fill(Node<T> x, Object[] out, int pos) {
        if (x == null) {
            return pos;
        }
        for (int i = 0; i < x.n; i++) {
            if (!x.leaf) {
                pos = fill(x.kids[i], out, pos);
            }
            out[pos++] = x.keys[i];
        }
        if (!x.leaf) {
            pos = fill(x.kids[x.n], out, pos);
        }
        return pos;
    }

    private void splitChild(Node<T> x, int i) {
        Node<T> y = x.kids[i];
        Node<T> z = new Node<T>(t, y.leaf);
        z.n = t - 1;
        for (int j = 0; j < t - 1; j++) {
            z.keys[j] = y.keys[j + t];
            y.keys[j + t] = null;
        }
        if (!y.leaf) {
            for (int j = 0; j < t; j++) {
                z.kids[j] = y.kids[j + t];
                y.kids[j + t] = null;
            }
        }
        Object median = y.keys[t - 1];
        y.keys[t - 1] = null;
        y.n = t - 1;

        for (int j = x.n; j > i; j--) {
            x.kids[j + 1] = x.kids[j];
        }
        x.kids[i + 1] = z;
        for (int j = x.n - 1; j >= i; j--) {
            x.keys[j + 1] = x.keys[j];
        }
        x.keys[i] = median;
        x.n++;
    }

    private void insertNonFull(Node<T> x, Object item) {
        while (true) {
            int i = lowerBound(x, item);
            if (x.leaf) {
                for (int j = x.n - 1; j >= i; j--) {
                    x.keys[j + 1] = x.keys[j];
                }
                x.keys[i] = item;
                x.n++;
                return;
            }
            Node<T> c = x.kids[i];
            if (c.n == 2 * t - 1) {
                splitChild(x, i);
                if (cmp(item, x.keys[i]) > 0) {
                    i++;
                }
                c = x.kids[i];
            }
            x = c;
        }
    }

    private void removeLocked(Object key) {
        Node<T> x = root;
        Object k = key;
        while (true) {
            int i = lowerBound(x, k);
            if (i < x.n && cmp(x.keys[i], k) == 0) {
                if (x.leaf) {
                    for (int j = i; j < x.n - 1; j++) {
                        x.keys[j] = x.keys[j + 1];
                    }
                    x.keys[x.n - 1] = null;
                    x.n--;
                    break;
                }
                Node<T> y = x.kids[i];
                Node<T> z = x.kids[i + 1];
                if (y.n >= t) {
                    Object pred = maxKey(y);
                    x.keys[i] = pred;
                    k = pred;
                    x = y;
                } else if (z.n >= t) {
                    Object succ = minKey(z);
                    x.keys[i] = succ;
                    k = succ;
                    x = z;
                } else {
                    merge(x, i);
                    x = y;
                }
            } else {
                Node<T> c = x.kids[i];
                if (c.n == t - 1) {
                    Node<T> left = i > 0 ? x.kids[i - 1] : null;
                    Node<T> right = i < x.n ? x.kids[i + 1] : null;
                    if (left != null && left.n >= t) {
                        borrowFromLeft(x, i);
                        c = x.kids[i];
                    } else if (right != null && right.n >= t) {
                        borrowFromRight(x, i);
                        c = x.kids[i];
                    } else if (right != null) {
                        merge(x, i);
                        c = x.kids[i];
                    } else {
                        merge(x, i - 1);
                        c = x.kids[i - 1];
                    }
                }
                x = c;
            }
        }
        if (root.n == 0) {
            root = root.leaf ? null : root.kids[0];
        }
    }

    // Merges kids[i], keys[i], and kids[i+1] of x into kids[i].
    private void merge(Node<T> x, int i) {
        Node<T> y = x.kids[i];
        Node<T> z = x.kids[i + 1];
        y.keys[y.n] = x.keys[i];
        for (int j = 0; j < z.n; j++) {
            y.keys[y.n + 1 + j] = z.keys[j];
        }
        if (!y.leaf) {
            for (int j = 0; j <= z.n; j++) {
                y.kids[y.n + 1 + j] = z.kids[j];
            }
        }
        y.n += z.n + 1;

        for (int j = i; j < x.n - 1; j++) {
            x.keys[j] = x.keys[j + 1];
        }
        x.keys[x.n - 1] = null;
        for (int j = i + 1; j < x.n; j++) {
            x.kids[j] = x.kids[j + 1];
        }
        x.kids[x.n] = null;
        x.n--;

        z.n = 0;
        for (int j = 0; j < z.keys.length; j++) {
            z.keys[j] = null;
        }
        for (int j = 0; j < z.kids.length; j++) {
            z.kids[j] = null;
        }
    }

    private void borrowFromLeft(Node<T> x, int i) {
        Node<T> c = x.kids[i];
        Node<T> left = x.kids[i - 1];
        for (int j = c.n; j > 0; j--) {
            c.keys[j] = c.keys[j - 1];
        }
        c.keys[0] = x.keys[i - 1];
        if (!c.leaf) {
            for (int j = c.n + 1; j > 0; j--) {
                c.kids[j] = c.kids[j - 1];
            }
            c.kids[0] = left.kids[left.n];
            left.kids[left.n] = null;
        }
        c.n++;
        x.keys[i - 1] = left.keys[left.n - 1];
        left.keys[left.n - 1] = null;
        left.n--;
    }

    private void borrowFromRight(Node<T> x, int i) {
        Node<T> c = x.kids[i];
        Node<T> right = x.kids[i + 1];
        c.keys[c.n] = x.keys[i];
        x.keys[i] = right.keys[0];
        for (int j = 0; j < right.n - 1; j++) {
            right.keys[j] = right.keys[j + 1];
        }
        right.keys[right.n - 1] = null;
        if (!c.leaf) {
            c.kids[c.n + 1] = right.kids[0];
            for (int j = 0; j < right.n; j++) {
                right.kids[j] = right.kids[j + 1];
            }
            right.kids[right.n] = null;
        }
        c.n++;
        right.n--;
    }
}
