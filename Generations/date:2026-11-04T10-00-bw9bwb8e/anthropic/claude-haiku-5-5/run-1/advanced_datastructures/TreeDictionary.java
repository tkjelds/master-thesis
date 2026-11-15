@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    public interface Order<K> {
        int compare(K left, K right);
    }

    public interface Entry<K, V> {
        K key();
        V value();
    }

    public interface Snapshot<K, V> {
        int size();
        V get(K key);
        Entry<K, V> entryAt(int index);
    }

    // Immutable red-black tree node. Nodes are never modified after construction,
    // so any reference to a node (root or otherwise) denotes a stable historical state.
    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int size;

        Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = sizeOf(left) + sizeOf(right) + 1;
        }
    }

    // Result of a functional deletion. shrunk == true means the returned subtree
    // has black height exactly one less than the subtree it replaced.
    private static final class Del<K, V> {
        final Node<K, V> node;
        final boolean shrunk;

        Del(Node<K, V> node, boolean shrunk) {
            this.node = node;
            this.shrunk = shrunk;
        }
    }

    private static final class EntryImpl<K, V> implements Entry<K, V> {
        private final K key;
        private final V value;

        EntryImpl(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public K key() {
            return key;
        }

        @Override
        public V value() {
            return value;
        }
    }

    private static final class SnapshotImpl<K, V> implements Snapshot<K, V> {
        private final Order<K> order;
        private final Node<K, V> root;

        SnapshotImpl(Order<K> order, Node<K, V> root) {
            this.order = order;
            this.root = root;
        }

        @Override
        public int size() {
            return sizeOf(root);
        }

        @Override
        public V get(K key) {
            requireNonNull(key, "key");
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c < 0) {
                    n = n.left;
                } else if (c > 0) {
                    n = n.right;
                } else {
                    return n.value;
                }
            }
            return null;
        }

        @Override
        public Entry<K, V> entryAt(int index) {
            int total = sizeOf(root);
            if (index < 0 || index >= total) {
                throw new IndexOutOfBoundsException("index " + index + " out of range for size " + total);
            }
            return entryAtNode(root, index);
        }
    }

    // ---- instance state ----
    private final Object lock = new Object();   // single collection-wide monitor
    private final Order<K> order;
    private Node<K, V> root;                    // guarded by lock

    // Policy: the collection is unbounded and no operation blocks. put/remove/get/etc.
    // complete immediately under the monitor. Invalid indices throw
    // IndexOutOfBoundsException immediately; null arguments throw NullPointerException
    // before any lock-protected state is touched.

    public TreeDictionary(Order<K> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (lock) {
            V previous = find(key);
            Node<K, V> newRoot = insert(root, key, value);   // may throw from comparator: no mutation yet
            root = black(newRoot);
            return previous;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            return find(key);
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            Node<K, V> target = findNode(key);
            if (target == null) {
                return null;
            }
            Del<K, V> d = del(root, key);
            root = (d.node == null) ? null : black(d.node);
            return target.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            int total = sizeOf(root);
            if (index < 0 || index >= total) {
                throw new IndexOutOfBoundsException("index " + index + " out of range for size " + total);
            }
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            int r = 0;
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c == 0) {
                    return r + sizeOf(n.left);
                } else if (c < 0) {
                    n = n.left;
                } else {
                    r += sizeOf(n.left) + 1;
                    n = n.right;
                }
            }
            return r;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<>(order, root);
        }
    }

    // ---- lookup helpers (called with lock held or on immutable nodes) ----

    private V find(K key) {
        Node<K, V> n = findNode(key);
        return n == null ? null : n.value;
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> n = root;
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c < 0) {
                n = n.left;
            } else if (c > 0) {
                n = n.right;
            } else {
                return n;
            }
        }
        return null;
    }

    private static <K, V> Entry<K, V> entryAtNode(Node<K, V> n, int index) {
        while (true) {
            int ls = sizeOf(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return new EntryImpl<>(n.key, n.value);
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
    }

    // ---- red-black persistent structure ----

    private static <K, V> int sizeOf(Node<K, V> n) {
        return n == null ? 0 : n.size;
    }

    private static <K, V> boolean isRed(Node<K, V> n) {
        return n != null && n.red;
    }

    private static <K, V> Node<K, V> black(Node<K, V> n) {
        return n.red ? new Node<>(n.key, n.value, false, n.left, n.right) : n;
    }

    private static <K, V> Node<K, V> minNode(Node<K, V> n) {
        while (n.left != null) {
            n = n.left;
        }
        return n;
    }

    // Okasaki-style balance for insertion, path-copying the affected nodes.
    private static <K, V> Node<K, V> balance(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        if (!red) {
            if (isRed(l) && isRed(l.left)) {
                return new Node<>(l.key, l.value, true,
                        black(l.left),
                        new Node<>(k, v, false, l.right, r));
            }
            if (isRed(l) && isRed(l.right)) {
                Node<K, V> lr = l.right;
                return new Node<>(lr.key, lr.value, true,
                        new Node<>(l.key, l.value, false, l.left, lr.left),
                        new Node<>(k, v, false, lr.right, r));
            }
            if (isRed(r) && isRed(r.left)) {
                Node<K, V> rl = r.left;
                return new Node<>(rl.key, rl.value, true,
                        new Node<>(k, v, false, l, rl.left),
                        new Node<>(r.key, r.value, false, rl.right, r.right));
            }
            if (isRed(r) && isRed(r.right)) {
                return new Node<>(r.key, r.value, true,
                        new Node<>(k, v, false, l, r.left),
                        black(r.right));
            }
        }
        return new Node<>(k, v, red, l, r);
    }

    private Node<K, V> insert(Node<K, V> n, K key, V value) {
        if (n == null) {
            return new Node<>(key, value, true, null, null);
        }
        int c = order.compare(key, n.key);
        if (c < 0) {
            return balance(n.red, n.key, n.value, insert(n.left, key, value), n.right);
        }
        if (c > 0) {
            return balance(n.red, n.key, n.value, n.left, insert(n.right, key, value));
        }
        // Replacement: retain the original key representative.
        return new Node<>(n.key, value, n.red, n.left, n.right);
    }

    // Precondition: key is present in the tree.
    private Del<K, V> del(Node<K, V> n, K key) {
        int c = order.compare(key, n.key);
        if (c < 0) {
            Del<K, V> d = del(n.left, key);
            if (!d.shrunk) {
                return new Del<>(new Node<>(n.key, n.value, n.red, d.node, n.right), false);
            }
            return leftDeficit(n.red, n.key, n.value, d.node, n.right);
        }
        if (c > 0) {
            Del<K, V> d = del(n.right, key);
            if (!d.shrunk) {
                return new Del<>(new Node<>(n.key, n.value, n.red, n.left, d.node), false);
            }
            return rightDeficit(n.red, n.key, n.value, n.left, d.node);
        }
        // n holds the key to remove.
        if (n.left == null || n.right == null) {
            Node<K, V> child = (n.left != null) ? n.left : n.right;
            if (n.red) {
                return new Del<>(child, false);          // child is null here
            }
            if (child != null) {
                return new Del<>(black(child), false);   // black node with red leaf child
            }
            return new Del<>(null, true);                // black leaf removed
        }
        Node<K, V> s = minNode(n.right);
        Del<K, V> d = del(n.right, s.key);
        if (!d.shrunk) {
            return new Del<>(new Node<>(s.key, s.value, n.red, n.left, d.node), false);
        }
        return rightDeficit(n.red, s.key, s.value, n.left, d.node);
    }

    // l is short by one black level relative to r.
    private Del<K, V> leftDeficit(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        if (r.red) {
            // Sibling red: rotate so the sibling becomes black, then fix the red subtree.
            Del<K, V> inner = leftDeficit(true, k, v, l, r.left);
            return new Del<>(new Node<>(r.key, r.value, false, inner.node, r.right), false);
        }
        if (isRed(r.right)) {
            return leftCaseC(red, k, v, l, r);
        }
        if (isRed(r.left)) {
            Node<K, V> x = r.left;
            Node<K, V> r2 = new Node<>(x.key, x.value, false,
                    x.left,
                    new Node<>(r.key, r.value, true, x.right, r.right));
            return leftCaseC(red, k, v, l, r2);
        }
        // Sibling black with black children: recolor sibling red.
        Node<K, V> newNode = new Node<>(k, v, false, l,
                new Node<>(r.key, r.value, true, r.left, r.right));
        return new Del<>(newNode, !red);
    }

    // Sibling r is black with a red right child.
    private Del<K, V> leftCaseC(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        Node<K, V> newLeft = new Node<>(k, v, false, l, r.left);
        Node<K, V> newRight = black(r.right);
        return new Del<>(new Node<>(r.key, r.value, red, newLeft, newRight), false);
    }

    // r is short by one black level relative to l.
    private Del<K, V> rightDeficit(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        if (l.red) {
            Del<K, V> inner = rightDeficit(true, k, v, l.right, r);
            return new Del<>(new Node<>(l.key, l.value, false, l.left, inner.node), false);
        }
        if (isRed(l.left)) {
            return rightCaseC(red, k, v, l, r);
        }
        if (isRed(l.right)) {
            Node<K, V> y = l.right;
            Node<K, V> l2 = new Node<>(y.key, y.value, false,
                    new Node<>(l.key, l.value, true, l.left, y.left),
                    y.right);
            return rightCaseC(red, k, v, l2, r);
        }
        Node<K, V> newNode = new Node<>(k, v, false,
                new Node<>(l.key, l.value, true, l.left, l.right), r);
        return new Del<>(newNode, !red);
    }

    // Sibling l is black with a red left child.
    private Del<K, V> rightCaseC(boolean red, K k, V v, Node<K, V> l, Node<K, V> r) {
        Node<K, V> newRight = new Node<>(k, v, false, l.right, r);
        Node<K, V> newLeft = black(l.left);
        return new Del<>(new Node<>(l.key, l.value, red, newLeft, newRight), false);
    }

    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }
}
