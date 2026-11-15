interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}


    private static final class Node {
        final boolean leaf;
        final Object[] keys;
        final Node[] children;
        int n;

        Node(boolean leaf, int maxKeys) {
            this.leaf = leaf;
            this.keys = new Object[maxKeys];
            this.children = leaf ? null : new Node[maxKeys + 1];
            this.n = 0;
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
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if ((long) minimumDegree * 2L > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("minimumDegree overflows node capacity");
        }
        this.t = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.order = order;
        this.root = null;
        this.size = 0;
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
                root = newNode(true);
            }
            if (root.n == maxKeys) {
                Node newRoot = newNode(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
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
            if (root == null || !containsLocked(item)) {
                return false;
            }
            removeFrom(root, item);
            if (root.n == 0) {
                root = root.leaf ? null : root.children[0];
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
            return containsLocked(item);
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty tree");
            }
            return minKey(root);
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("empty tree");
            }
            return maxKey(root);
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
            int end = collect(root, out, 0);
            if (end != out.length) {
                throw new IllegalStateException("inconsistent size");
            }
            return out;
        }
    }

    // ---- internal helpers (all called while holding lock) ----

    private Node newNode(boolean leaf) {
        return new Node(leaf, maxKeys);
    }

    @SuppressWarnings("unchecked")
    private T key(Node x, int i) {
        return (T) x.keys[i];
    }

    private int lowerBound(Node x, T item) {
        int lo = 0;
        int hi = x.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (order.compare(key(x, mid), item) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private boolean containsLocked(T item) {
        Node x = root;
        while (x != null) {
            int i = lowerBound(x, item);
            if (i < x.n && order.compare(key(x, i), item) == 0) {
                return true;
            }
            if (x.leaf) {
                return false;
            }
            x = x.children[i];
        }
        return false;
    }

    private T minKey(Node x) {
        while (!x.leaf) {
            x = x.children[0];
        }
        return key(x, 0);
    }

    private T maxKey(Node x) {
        while (!x.leaf) {
            x = x.children[x.n];
        }
        return key(x, x.n - 1);
    }

    private int collect(Node x, Object[] out, int pos) {
        if (x == null) {
            return pos;
        }
        if (x.leaf) {
            for (int i = 0; i < x.n; i++) {
                out[pos++] = x.keys[i];
            }
            return pos;
        }
        for (int i = 0; i < x.n; i++) {
            pos = collect(x.children[i], out, pos);
            out[pos++] = x.keys[i];
        }
        return collect(x.children[x.n], out, pos);
    }

    // Splits full child p.children[i] (2t-1 keys) around its median.
    private void splitChild(Node p, int i) {
        Node y = p.children[i];
        Node z = newNode(y.leaf);
        Object median = y.keys[t - 1];

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
        z.n = t - 1;
        y.keys[t - 1] = null;
        y.n = t - 1;

        for (int j = p.n; j >= i + 1; j--) {
            p.children[j + 1] = p.children[j];
        }
        for (int j = p.n - 1; j >= i; j--) {
            p.keys[j + 1] = p.keys[j];
        }
        p.children[i + 1] = z;
        p.keys[i] = median;
        p.n++;
    }

    // Precondition: x is not full.
    private void insertNonFull(Node x, T item) {
        while (true) {
            if (x.leaf) {
                int i = x.n;
                while (i > 0 && order.compare(key(x, i - 1), item) > 0) {
                    x.keys[i] = x.keys[i - 1];
                    i--;
                }
                x.keys[i] = item;
                x.n++;
                return;
            }
            int i = lowerBound(x, item);
            if (x.children[i].n == maxKeys) {
                splitChild(x, i);
                if (order.compare(item, key(x, i)) > 0) {
                    i++;
                }
            }
            x = x.children[i];
        }
    }

    // Precondition: x is root or has at least t keys; item is present in subtree of x.
    private void removeFrom(Node x, T item) {
        while (true) {
            int i = lowerBound(x, item);
            if (i < x.n && order.compare(key(x, i), item) == 0) {
                if (x.leaf) {
                    removeKeyAt(x, i);
                    return;
                }
                Node left = x.children[i];
                Node right = x.children[i + 1];
                if (left.n >= t) {
                    T pred = maxKey(left);
                    x.keys[i] = pred;
                    item = pred;
                    x = left;
                } else if (right.n >= t) {
                    T succ = minKey(right);
                    x.keys[i] = succ;
                    item = succ;
                    x = right;
                } else {
                    mergeChildren(x, i);
                    x = left;
                }
                continue;
            }

            // Item not in x; x must be internal since containment was verified.
            int idx = i;
            Node child = x.children[idx];
            if (child.n == t - 1) {
                Node left = idx > 0 ? x.children[idx - 1] : null;
                Node right = idx < x.n ? x.children[idx + 1] : null;
                if (left != null && left.n >= t) {
                    borrowFromLeft(x, idx);
                } else if (right != null && right.n >= t) {
                    borrowFromRight(x, idx);
                } else if (left != null) {
                    mergeChildren(x, idx - 1);
                    idx = idx - 1;
                } else {
                    mergeChildren(x, idx);
                }
                child = x.children[idx];
            }
            x = child;
        }
    }

    private void removeKeyAt(Node x, int i) {
        for (int j = i; j < x.n - 1; j++) {
            x.keys[j] = x.keys[j + 1];
        }
        x.keys[x.n - 1] = null;
        x.n--;
    }

    private void borrowFromLeft(Node x, int i) {
        Node child = x.children[i];
        Node left = x.children[i - 1];

        for (int j = child.n; j >= 1; j--) {
            child.keys[j] = child.keys[j - 1];
        }
        if (!child.leaf) {
            for (int j = child.n + 1; j >= 1; j--) {
                child.children[j] = child.children[j - 1];
            }
            child.children[0] = left.children[left.n];
            left.children[left.n] = null;
        }
        child.keys[0] = x.keys[i - 1];
        x.keys[i - 1] = left.keys[left.n - 1];
        left.keys[left.n - 1] = null;
        left.n--;
        child.n++;
    }

    private void borrowFromRight(Node x, int i) {
        Node child = x.children[i];
        Node right = x.children[i + 1];

        child.keys[child.n] = x.keys[i];
        if (!child.leaf) {
            child.children[child.n + 1] = right.children[0];
        }
        x.keys[i] = right.keys[0];

        for (int j = 0; j < right.n - 1; j++) {
            right.keys[j] = right.keys[j + 1];
        }
        right.keys[right.n - 1] = null;
        if (!right.leaf) {
            for (int j = 0; j < right.n; j++) {
                right.children[j] = right.children[j + 1];
            }
            right.children[right.n] = null;
        }
        right.n--;
        child.n++;
    }

    // Merges x.children[i], separator x.keys[i], and x.children[i+1] into x.children[i].
    private void mergeChildren(Node x, int i) {
        Node left = x.children[i];
        Node right = x.children[i + 1];
        int ln = left.n;

        left.keys[ln] = x.keys[i];
        for (int j = 0; j < right.n; j++) {
            left.keys[ln + 1 + j] = right.keys[j];
        }
        if (!left.leaf) {
            for (int j = 0; j <= right.n; j++) {
                left.children[ln + 1 + j] = right.children[j];
            }
        }
        left.n = ln + right.n + 1;

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
}
