@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    private final int t;
    private final Order<T> order;
    private final Object lock = new Object();
    // Guarded by lock. Null root means empty tree.
    private Node<T> root;
    // Guarded by lock.
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if (2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("minimumDegree node arrays overflow int");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.t = minimumDegree;
        this.order = order;
        this.root = null;
        this.size = 0;
    }

    /**
     * Adds item. Returns false (without replacing) if an equivalent element exists.
     * Never blocks beyond the tree monitor; the structure is unbounded.
     */
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
            }
            if (root.count == 2 * t - 1) {
                Node<T> newRoot = new Node<T>(t, false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }
            insertNonFull(root, item);
            size++;
            return true;
        }
    }

    /**
     * Removes item. Returns false if no equivalent element exists.
     */
    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (!containsLocked(item)) {
                return false;
            }
            deleteLocked(item);
            size--;
            if (size == 0) {
                root = null;
            }
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

    /**
     * Returns the smallest element; throws IllegalStateException if empty.
     */
    public T first() {
        synchronized (lock) {
            if (size == 0 || root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> n = root;
            while (!n.leaf) {
                n = n.children[0];
            }
            return keyAt(n, 0);
        }
    }

    /**
     * Returns the largest element; throws IllegalStateException if empty.
     */
    public T last() {
        synchronized (lock) {
            if (size == 0 || root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> n = root;
            while (!n.leaf) {
                n = n.children[n.count];
            }
            return keyAt(n, n.count - 1);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /**
     * Returns a detached array of all elements in sorted order, captured atomically.
     */
    public Object[] snapshot() {
        synchronized (lock) {
            Object[] out = new Object[size];
            if (root != null) {
                fill(root, out, 0);
            }
            return out;
        }
    }

    // ---------------- internal helpers (caller holds lock) ----------------

    @SuppressWarnings("unchecked")
    private T keyAt(Node<T> x, int i) {
        return (T) x.keys[i];
    }

    /** First index i in [0, count] such that keys[i] >= key. */
    private int lowerBound(Node<T> x, T key) {
        int lo = 0;
        int hi = x.count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (order.compare(keyAt(x, mid), key) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private boolean containsLocked(T item) {
        Node<T> x = root;
        while (x != null) {
            int i = lowerBound(x, item);
            if (i < x.count && order.compare(keyAt(x, i), item) == 0) {
                return true;
            }
            if (x.leaf) {
                return false;
            }
            x = x.children[i];
        }
        return false;
    }

    /** Splits full child x.children[i] into two nodes, moving median up into x. */
    private void splitChild(Node<T> x, int i) {
        Node<T> y = x.children[i];
        Node<T> z = new Node<T>(t, y.leaf);
        z.count = t - 1;
        System.arraycopy(y.keys, t, z.keys, 0, t - 1);
        if (!y.leaf) {
            System.arraycopy(y.children, t, z.children, 0, t);
        }
        Object median = y.keys[t - 1];
        for (int j = t - 1; j < 2 * t - 1; j++) {
            y.keys[j] = null;
        }
        if (!y.leaf) {
            for (int j = t; j < 2 * t; j++) {
                y.children[j] = null;
            }
        }
        y.count = t - 1;

        System.arraycopy(x.children, i + 1, x.children, i + 2, x.count - i);
        x.children[i + 1] = z;
        System.arraycopy(x.keys, i, x.keys, i + 1, x.count - i);
        x.keys[i] = median;
        x.count++;
    }

    /** Inserts into a non-full node subtree; item is known to be absent. */
    private void insertNonFull(Node<T> x, T item) {
        while (true) {
            int i = lowerBound(x, item);
            if (x.leaf) {
                System.arraycopy(x.keys, i, x.keys, i + 1, x.count - i);
                x.keys[i] = item;
                x.count++;
                return;
            }
            Node<T> child = x.children[i];
            if (child.count == 2 * t - 1) {
                splitChild(x, i);
                if (order.compare(item, keyAt(x, i)) > 0) {
                    i++;
                }
                child = x.children[i];
            }
            x = child;
        }
    }

    /** Deletes key, which is known to be present in the tree. */
    private void deleteLocked(T key) {
        Node<T> x = root;
        while (true) {
            int i = lowerBound(x, key);
            if (i < x.count && order.compare(keyAt(x, i), key) == 0) {
                if (x.leaf) {
                    removeKeyAt(x, i);
                    return;
                }
                Node<T> y = x.children[i];
                Node<T> z = x.children[i + 1];
                if (y.count >= t) {
                    T pred = maxKey(y);
                    x.keys[i] = pred;
                    key = pred;
                    x = y;
                } else if (z.count >= t) {
                    T succ = minKey(z);
                    x.keys[i] = succ;
                    key = succ;
                    x = z;
                } else {
                    mergeChildren(x, i);
                    if (x == root && x.count == 0) {
                        root = y;
                    }
                    x = y;
                }
                continue;
            }

            // Key is not in x; it lies in child i. Ensure child has at least t keys.
            Node<T> child = x.children[i];
            if (child.count == t - 1) {
                Node<T> left = i > 0 ? x.children[i - 1] : null;
                Node<T> right = i < x.count ? x.children[i + 1] : null;
                if (left != null && left.count >= t) {
                    borrowFromLeft(x, i);
                } else if (right != null && right.count >= t) {
                    borrowFromRight(x, i);
                } else {
                    if (right != null) {
                        mergeChildren(x, i);
                    } else {
                        mergeChildren(x, i - 1);
                        i = i - 1;
                    }
                }
                Node<T> next = x.children[i];
                if (x == root && x.count == 0) {
                    root = next;
                }
                x = next;
            } else {
                x = child;
            }
        }
    }

    private T maxKey(Node<T> y) {
        Node<T> n = y;
        while (!n.leaf) {
            n = n.children[n.count];
        }
        return keyAt(n, n.count - 1);
    }

    private T minKey(Node<T> z) {
        Node<T> n = z;
        while (!n.leaf) {
            n = n.children[0];
        }
        return keyAt(n, 0);
    }

    private void removeKeyAt(Node<T> x, int i) {
        System.arraycopy(x.keys, i + 1, x.keys, i, x.count - i - 1);
        x.count--;
        x.keys[x.count] = null;
    }

    /** Moves one key from left sibling through parent into child x.children[i]. */
    private void borrowFromLeft(Node<T> x, int i) {
        Node<T> child = x.children[i];
        Node<T> left = x.children[i - 1];
        System.arraycopy(child.keys, 0, child.keys, 1, child.count);
        if (!child.leaf) {
            System.arraycopy(child.children, 0, child.children, 1, child.count + 1);
            child.children[0] = left.children[left.count];
            left.children[left.count] = null;
        }
        child.keys[0] = x.keys[i - 1];
        child.count++;
        x.keys[i - 1] = left.keys[left.count - 1];
        left.keys[left.count - 1] = null;
        left.count--;
    }

    /** Moves one key from right sibling through parent into child x.children[i]. */
    private void borrowFromRight(Node<T> x, int i) {
        Node<T> child = x.children[i];
        Node<T> right = x.children[i + 1];
        child.keys[child.count] = x.keys[i];
        if (!child.leaf) {
            child.children[child.count + 1] = right.children[0];
        }
        child.count++;
        x.keys[i] = right.keys[0];
        System.arraycopy(right.keys, 1, right.keys, 0, right.count - 1);
        right.keys[right.count - 1] = null;
        if (!right.leaf) {
            System.arraycopy(right.children, 1, right.children, 0, right.count);
            right.children[right.count] = null;
        }
        right.count--;
    }

    /** Merges x.children[i+1] and separator x.keys[i] into x.children[i]. */
    private void mergeChildren(Node<T> x, int i) {
        Node<T> y = x.children[i];
        Node<T> z = x.children[i + 1];
        y.keys[y.count] = x.keys[i];
        System.arraycopy(z.keys, 0, y.keys, y.count + 1, z.count);
        if (!y.leaf) {
            System.arraycopy(z.children, 0, y.children, y.count + 1, z.count + 1);
        }
        y.count += z.count + 1;

        System.arraycopy(x.keys, i + 1, x.keys, i, x.count - i - 1);
        x.keys[x.count - 1] = null;
        System.arraycopy(x.children, i + 2, x.children, i + 1, x.count - i - 1);
        x.children[x.count] = null;
        x.count--;
    }

    private int fill(Node<T> x, Object[] out, int pos) {
        for (int i = 0; i <= x.count; i++) {
            if (!x.leaf) {
                pos = fill(x.children[i], out, pos);
            }
            if (i < x.count) {
                out[pos++] = x.keys[i];
            }
        }
        return pos;
    }

    private static final class Node<E> {
        final Object[] keys;
        final Node<E>[] children;
        final boolean leaf;
        int count;

        @SuppressWarnings("unchecked")
        Node(int t, boolean leaf) {
            this.keys = new Object[2 * t - 1];
            this.leaf = leaf;
            this.children = leaf ? null : (Node<E>[]) new Node[2 * t];
            this.count = 0;
        }
    }
}

interface Order<T> {
    int compare(T left, T right);
}
