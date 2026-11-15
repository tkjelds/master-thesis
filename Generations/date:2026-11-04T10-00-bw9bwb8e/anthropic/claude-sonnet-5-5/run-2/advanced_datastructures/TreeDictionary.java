interface Order<K> {
    int compare(K left, K right);
}

@Entry.ThreadSafe
final class Entry<K, V> {
    @interface ThreadSafe {}

    private final K key;
    private final V value;

    Entry(K key, V value) {
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

interface Snapshot<K, V> {
    int size();

    V get(K key);

    Entry<K, V> entryAt(int index);
}

@TreeDictionary.ThreadSafe
public class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    // Immutable persistent node.
    private static final class Node<K, V> {
        final K key;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;
        final boolean red;
        final int size;

        Node(K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
        }
    }

    // Stateless (apart from the order) tree algorithms over immutable nodes.
    private static final class Tree<K, V> {
        final Order<K> order;

        Tree(Order<K> order) {
            this.order = order;
        }

        int size(Node<K, V> n) {
            return n == null ? 0 : n.size;
        }

        boolean isRed(Node<K, V> n) {
            return n != null && n.red;
        }

        Node<K, V> mk(K k, V v, Node<K, V> l, Node<K, V> r, boolean red) {
            return new Node<K, V>(k, v, l, r, red);
        }

        Node<K, V> recolor(Node<K, V> n, boolean red) {
            if (n == null || n.red == red) return n;
            return new Node<K, V>(n.key, n.value, n.left, n.right, red);
        }

        Node<K, V> find(Node<K, V> root, K key) {
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c == 0) return n;
                n = c < 0 ? n.left : n.right;
            }
            return null;
        }

        int rank(Node<K, V> root, K key) {
            int r = 0;
            Node<K, V> n = root;
            while (n != null) {
                int c = order.compare(key, n.key);
                if (c <= 0) {
                    n = n.left;
                } else {
                    r += size(n.left) + 1;
                    n = n.right;
                }
            }
            return r;
        }

        Entry<K, V> entryAt(Node<K, V> root, int index) {
            if (index < 0 || index >= size(root)) {
                throw new IndexOutOfBoundsException("index " + index + ", size " + size(root));
            }
            Node<K, V> n = root;
            int i = index;
            while (true) {
                int ls = size(n.left);
                if (i < ls) {
                    n = n.left;
                } else if (i == ls) {
                    return new Entry<K, V>(n.key, n.value);
                } else {
                    i -= ls + 1;
                    n = n.right;
                }
            }
        }

        Node<K, V> rotateLeft(Node<K, V> h) {
            Node<K, V> x = h.right;
            Node<K, V> nh = mk(h.key, h.value, h.left, x.left, true);
            return mk(x.key, x.value, nh, x.right, h.red);
        }

        Node<K, V> rotateRight(Node<K, V> h) {
            Node<K, V> x = h.left;
            Node<K, V> nh = mk(h.key, h.value, x.right, h.right, true);
            return mk(x.key, x.value, x.left, nh, h.red);
        }

        Node<K, V> flip(Node<K, V> h) {
            return mk(h.key, h.value,
                    h.left == null ? null : recolor(h.left, !h.left.red),
                    h.right == null ? null : recolor(h.right, !h.right.red),
                    !h.red);
        }

        Node<K, V> fixUp(Node<K, V> h) {
            if (isRed(h.right) && !isRed(h.left)) h = rotateLeft(h);
            if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
            if (isRed(h.left) && isRed(h.right)) h = flip(h);
            return h;
        }

        Node<K, V> insert(Node<K, V> h, K key, V value, Object[] old) {
            if (h == null) return mk(key, value, null, null, true);
            int c = order.compare(key, h.key);
            if (c < 0) {
                h = mk(h.key, h.value, insert(h.left, key, value, old), h.right, h.red);
            } else if (c > 0) {
                h = mk(h.key, h.value, h.left, insert(h.right, key, value, old), h.red);
            } else {
                old[0] = h.value;
                h = mk(h.key, value, h.left, h.right, h.red);
            }
            return fixUp(h);
        }

        Node<K, V> moveRedLeft(Node<K, V> h) {
            h = flip(h);
            if (isRed(h.right.left)) {
                h = mk(h.key, h.value, h.left, rotateRight(h.right), h.red);
                h = rotateLeft(h);
                h = flip(h);
            }
            return h;
        }

        Node<K, V> moveRedRight(Node<K, V> h) {
            h = flip(h);
            if (isRed(h.left.left)) {
                h = rotateRight(h);
                h = flip(h);
            }
            return h;
        }

        Node<K, V> min(Node<K, V> h) {
            while (h.left != null) h = h.left;
            return h;
        }

        Node<K, V> deleteMin(Node<K, V> h) {
            if (h.left == null) return null;
            if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
            h = mk(h.key, h.value, deleteMin(h.left), h.right, h.red);
            return fixUp(h);
        }

        // Precondition: key is present in the subtree.
        Node<K, V> delete(Node<K, V> h, K key) {
            if (order.compare(key, h.key) < 0) {
                if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
                h = mk(h.key, h.value, delete(h.left, key), h.right, h.red);
            } else {
                if (isRed(h.left)) h = rotateRight(h);
                if (order.compare(key, h.key) == 0 && h.right == null) return null;
                if (!isRed(h.right) && !isRed(h.right.left)) h = moveRedRight(h);
                if (order.compare(key, h.key) == 0) {
                    Node<K, V> m = min(h.right);
                    h = mk(m.key, m.value, h.left, deleteMin(h.right), h.red);
                } else {
                    h = mk(h.key, h.value, h.left, delete(h.right, key), h.red);
                }
            }
            return fixUp(h);
        }
    }

    private static final class SnapshotImpl<K, V> implements Snapshot<K, V> {
        private final Tree<K, V> tree;
        private final Node<K, V> root;

        SnapshotImpl(Tree<K, V> tree, Node<K, V> root) {
            this.tree = tree;
            this.root = root;
        }

        public int size() {
            return tree.size(root);
        }

        public V get(K key) {
            if (key == null) throw new NullPointerException("key");
            Node<K, V> n = tree.find(root, key);
            return n == null ? null : n.value;
        }

        public Entry<K, V> entryAt(int index) {
            return tree.entryAt(root, index);
        }
    }

    private final Object lock = new Object();
    private final Tree<K, V> tree;
    private Node<K, V> root; // guarded by lock

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException("order");
        this.tree = new Tree<K, V>(order);
    }

    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        synchronized (lock) {
            Object[] old = new Object[1];
            Node<K, V> nr = tree.insert(root, key, value, old);
            root = tree.recolor(nr, false);
            @SuppressWarnings("unchecked")
            V prev = (V) old[0];
            return prev;
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            Node<K, V> n = tree.find(root, key);
            return n == null ? null : n.value;
        }
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            Node<K, V> n = tree.find(root, key);
            if (n == null) return null;
            V prev = n.value;
            Node<K, V> r = root;
            if (!tree.isRed(r.left) && !tree.isRed(r.right)) {
                r = tree.recolor(r, true);
            }
            r = tree.delete(r, key);
            root = r == null ? null : tree.recolor(r, false);
            return prev;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            return tree.find(root, key) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return tree.size(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            return tree.entryAt(root, index);
        }
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            return tree.rank(root, key);
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new SnapshotImpl<K, V>(tree, root);
        }
    }
}
