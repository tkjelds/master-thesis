import java.lang.*;

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

        Node(int maxKeys, boolean leaf) {
            keys = new Object[maxKeys];
            children = leaf ? null : new Node[maxKeys + 1];
        }

        boolean isLeaf() {
            return children == null;
        }
    }

    private final int t;
    private final int maxKeys;
    private final Order<T> order;
    private final Object lock = new Object();

    private Node root; // null when empty
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        if (minimumDegree < 2 || minimumDegree > (Integer.MAX_VALUE / 2)) {
            throw new IllegalArgumentException("invalid minimumDegree");
        }
        this.t = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node x, int i) {
        return (T) x.keys[i];
    }

    // Returns index if found, else -(insertionPoint+1).
    private int find(Node x, T k) {
        int lo = 0, hi = x.n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = order.compare(k, keyAt(x, mid));
            if (c == 0) {
                return mid;
            } else if (c < 0) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return -(lo + 1);
    }

    private boolean containsInternal(T item) {
        Node x = root;
        while (x != null) {
            int i = find(x, item);
            if (i >= 0) {
                return true;
            }
            if (x.isLeaf()) {
                return false;
            }
            x = x.children[-(i + 1)];
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
            if (containsInternal(item)) {
                return false;
            }
            if (root == null) {
                root = new Node(maxKeys, true);
                root.keys[0] = item;
                root.n = 1;
                size++;
                return true;
            }
            if (root.n == maxKeys) {
                Node s = new Node(maxKeys, false);
                s.children[0] = root;
                splitChild(s, 0);
                root = s;
            }
            Node x = root;
            while (true) {
                int pos = -(find(x, item) + 1);
                if (x.isLeaf()) {
                    for (int j = x.n; j > pos; j--) {
                        x.keys[j] = x.keys[j - 1];
                    }
                    x.keys[pos] = item;
                    x.n++;
                    break;
                }
                if (x.children[pos].n == maxKeys) {
                    splitChild(x, pos);
                    if (order.compare(item, keyAt(x, pos)) > 0) {
                        pos++;
                    }
                }
                x = x.children[pos];
            }
            size++;
            return true;
        }
    }

    private void splitChild(Node p, int i) {
        Node y = p.children[i];
        Node z = new Node(maxKeys, y.isLeaf());
        z.n = t - 1;
        for (int j = 0; j < t - 1; j++) {
            z.keys[j] = y.keys[t + j];
            y.keys[t + j] = null;
        }
        if (!y.isLeaf()) {
            for (int j = 0; j < t; j++) {
                z.children[j] = y.children[t + j];
                y.children[t + j] = null;
            }
        }
        Object median = y.keys[t - 1];
        y.keys[t - 1] = null;
        y.n = t - 1;
        for (int j = p.n; j > i; j--) {
            p.keys[j] = p.keys[j - 1];
        }
        for (int j = p.n + 1; j > i + 1; j--) {
            p.children[j] = p.children[j - 1];
        }
        p.keys[i] = median;
        p.children[i + 1] = z;
        p.n++;
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (!containsInternal(item)) {
                return false;
            }
            T k = item;
            Node x = root;
            while (true) {
                int idx = find(x, k);
                if (idx >= 0) {
                    if (x.isLeaf()) {
                        for (int j = idx; j < x.n - 1; j++) {
                            x.keys[j] = x.keys[j + 1];
                        }
                        x.keys[x.n - 1] = null;
                        x.n--;
                        break;
                    }
                    Node y = x.children[idx];
                    Node z = x.children[idx + 1];
                    if (y.n >= t) {
                        Node m = y;
                        while (!m.isLeaf()) {
                            m = m.children[m.n];
                        }
                        T pred = keyAt(m, m.n - 1);
                        x.keys[idx] = pred;
                        k = pred;
                        x = y;
                    } else if (z.n >= t) {
                        Node m = z;
                        while (!m.isLeaf()) {
                            m = m.children[0];
                        }
                        T succ = keyAt(m, 0);
                        x.keys[idx] = succ;
                        k = succ;
                        x = z;
                    } else {
                        merge(x, idx);
                        x = y;
                    }
                } else {
                    int i = -(idx + 1);
                    if (x.isLeaf()) {
                        break; // cannot happen
                    }
                    Node c = x.children[i];
                    if (c.n == t - 1) {
                        if (i > 0 && x.children[i - 1].n >= t) {
                            borrowFromLeft(x, i);
                        } else if (i < x.n && x.children[i + 1].n >= t) {
                            borrowFromRight(x, i);
                        } else if (i < x.n) {
                            merge(x, i);
                        } else {
                            merge(x, i - 1);
                            c = x.children[i - 1];
                        }
                    }
                    x = c;
                }
            }
            if (root.n == 0) {
                root = root.isLeaf() ? null : root.children[0];
            }
            size--;
            return true;
        }
    }

    private void borrowFromLeft(Node x, int i) {
        Node c = x.children[i];
        Node l = x.children[i - 1];
        for (int j = c.n; j > 0; j--) {
            c.keys[j] = c.keys[j - 1];
        }
        if (!c.isLeaf()) {
            for (int j = c.n + 1; j > 0; j--) {
                c.children[j] = c.children[j - 1];
            }
            c.children[0] = l.children[l.n];
            l.children[l.n] = null;
        }
        c.keys[0] = x.keys[i - 1];
        x.keys[i - 1] = l.keys[l.n - 1];
        l.keys[l.n - 1] = null;
        l.n--;
        c.n++;
    }

    private void borrowFromRight(Node x, int i) {
        Node c = x.children[i];
        Node r = x.children[i + 1];
        c.keys[c.n] = x.keys[i];
        if (!c.isLeaf()) {
            c.children[c.n + 1] = r.children[0];
            for (int j = 0; j < r.n; j++) {
                r.children[j] = r.children[j + 1];
            }
            r.children[r.n] = null;
        }
        x.keys[i] = r.keys[0];
        for (int j = 0; j < r.n - 1; j++) {
            r.keys[j] = r.keys[j + 1];
        }
        r.keys[r.n - 1] = null;
        r.n--;
        c.n++;
    }

    // Merges children[i], keys[i], children[i+1] into children[i].
    private void merge(Node x, int i) {
        Node y = x.children[i];
        Node z = x.children[i + 1];
        y.keys[y.n] = x.keys[i];
        for (int j = 0; j < z.n; j++) {
            y.keys[y.n + 1 + j] = z.keys[j];
        }
        if (!y.isLeaf()) {
            for (int j = 0; j <= z.n; j++) {
                y.children[y.n + 1 + j] = z.children[j];
            }
        }
        y.n += z.n + 1;
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

    public T first() {
        synchronized (lock) {
            if (size == 0 || root == null) {
                throw new IllegalStateException("empty");
            }
            Node x = root;
            while (!x.isLeaf()) {
                x = x.children[0];
            }
            return keyAt(x, 0);
        }
    }

    public T last() {
        synchronized (lock) {
            if (size == 0 || root == null) {
                throw new IllegalStateException("empty");
            }
            Node x = root;
            while (!x.isLeaf()) {
                x = x.children[x.n];
            }
            return keyAt(x, x.n - 1);
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
        if (x.isLeaf()) {
            for (int i = 0; i < x.n; i++) {
                out[pos++] = x.keys[i];
            }
            return pos;
        }
        for (int i = 0; i < x.n; i++) {
            pos = fill(x.children[i], out, pos);
            out[pos++] = x.keys[i];
        }
        return fill(x.children[x.n], out, pos);
    }
}
