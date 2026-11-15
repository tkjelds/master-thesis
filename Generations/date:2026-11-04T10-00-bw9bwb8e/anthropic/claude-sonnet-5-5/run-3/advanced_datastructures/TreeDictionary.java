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

@Snapshot.ThreadSafe
final class Snapshot<K, V> {
    @interface ThreadSafe {}

    private final TreeDictionary.Node<K, V> root;
    private final Order<K> order;

    Snapshot(TreeDictionary.Node<K, V> root, Order<K> order) {
        this.root = root;
        this.order = order;
    }

    public int size() {
        return TreeDictionary.sz(root);
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        TreeDictionary.Node<K, V> n = root;
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c == 0) return n.value;
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    public Entry<K, V> entryAt(int index) {
        return TreeDictionary.select(root, index);
    }
}

@TreeDictionary.ThreadSafe
public class TreeDictionary<K, V> {
    @interface ThreadSafe {}


    static final class Node<K, V> {
        final K key;
        final V value;
        final Node<K, V> left, right;
        final boolean red;
        final int size;

        Node(K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + sz(left) + sz(right);
        }
    }

    private final Order<K> order;
    private final Object lock = new Object();
    private volatile Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        if (order == null) throw new NullPointerException("order");
        this.order = order;
    }

    static <K, V> int sz(Node<K, V> n) {
        return n == null ? 0 : n.size;
    }

    private static <K, V> boolean isRed(Node<K, V> n) {
        return n != null && n.red;
    }

    private static <K, V> Node<K, V> mk(K k, V v, Node<K, V> l, Node<K, V> r, boolean red) {
        return new Node<K, V>(k, v, l, r, red);
    }

    private static <K, V> Node<K, V> setLeft(Node<K, V> h, Node<K, V> l) {
        return mk(h.key, h.value, l, h.right, h.red);
    }

    private static <K, V> Node<K, V> setRight(Node<K, V> h, Node<K, V> r) {
        return mk(h.key, h.value, h.left, r, h.red);
    }

    private static <K, V> Node<K, V> withRed(Node<K, V> h, boolean red) {
        if (h.red == red) return h;
        return mk(h.key, h.value, h.left, h.right, red);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> h) {
        Node<K, V> x = h.right;
        Node<K, V> nh = mk(h.key, h.value, h.left, x.left, true);
        return mk(x.key, x.value, nh, x.right, h.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> h) {
        Node<K, V> x = h.left;
        Node<K, V> nh = mk(h.key, h.value, x.right, h.right, true);
        return mk(x.key, x.value, x.left, nh, h.red);
    }

    private static <K, V> Node<K, V> flip(Node<K, V> h) {
        return mk(h.key, h.value,
                withRed(h.left, !h.left.red),
                withRed(h.right, !h.right.red),
                !h.red);
    }

    private static <K, V> Node<K, V> fixUp(Node<K, V> h) {
        if (isRed(h.right) && !isRed(h.left)) h = rotateLeft(h);
        if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
        if (isRed(h.left) && isRed(h.right)) h = flip(h);
        return h;
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> h) {
        h = flip(h);
        if (isRed(h.right.left)) {
            h = setRight(h, rotateRight(h.right));
            h = rotateLeft(h);
            h = flip(h);
        }
        return h;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> h) {
        h = flip(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
            h = flip(h);
        }
        return h;
    }

    private Node<K, V> insert(Node<K, V> h, K key, V value, Object[] old) {
        if (h == null) return mk(key, value, null, null, true);
        int c = order.compare(key, h.key);
        if (c < 0) {
            h = setLeft(h, insert(h.left, key, value, old));
        } else if (c > 0) {
            h = setRight(h, insert(h.right, key, value, old));
        } else {
            old[0] = h.value;
            h = mk(h.key, value, h.left, h.right, h.red);
        }
        return fixUp(h);
    }

    private static <K, V> Node<K, V> min(Node<K, V> h) {
        while (h.left != null) h = h.left;
        return h;
    }

    private Node<K, V> deleteMin(Node<K, V> h) {
        if (h.left == null) return null;
        if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
        h = setLeft(h, deleteMin(h.left));
        return fixUp(h);
    }

    // precondition: key is present in subtree h
    private Node<K, V> delete(Node<K, V> h, K key) {
        if (order.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
            h = setLeft(h, delete(h.left, key));
        } else {
            if (isRed(h.left)) h = rotateRight(h);
            if (order.compare(key, h.key) == 0 && h.right == null) return null;
            if (!isRed(h.right) && !isRed(h.right.left)) h = moveRedRight(h);
            if (order.compare(key, h.key) == 0) {
                Node<K, V> m = min(h.right);
                h = mk(m.key, m.value, h.left, deleteMin(h.right), h.red);
            } else {
                h = setRight(h, delete(h.right, key));
            }
        }
        return fixUp(h);
    }

    private Node<K, V> find(Node<K, V> n, K key) {
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c == 0) return n;
            n = c < 0 ? n.left : n.right;
        }
        return null;
    }

    static <K, V> Entry<K, V> select(Node<K, V> root, int index) {
        if (index < 0 || index >= sz(root)) {
            throw new IndexOutOfBoundsException("index " + index + ", size " + sz(root));
        }
        Node<K, V> n = root;
        int i = index;
        while (true) {
            int ls = sz(n.left);
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

    public V put(K key, V value) {
        if (key == null) throw new NullPointerException("key");
        if (value == null) throw new NullPointerException("value");
        synchronized (lock) {
            Object[] old = new Object[1];
            Node<K, V> nr = insert(root, key, value, old);
            nr = withRed(nr, false);
            root = nr;
            @SuppressWarnings("unchecked")
            V prev = (V) old[0];
            return prev;
        }
    }

    public V get(K key) {
        if (key == null) throw new NullPointerException("key");
        Node<K, V> n = find(root, key);
        return n == null ? null : n.value;
    }

    public V remove(K key) {
        if (key == null) throw new NullPointerException("key");
        synchronized (lock) {
            Node<K, V> r = root;
            Node<K, V> found = find(r, key);
            if (found == null) return null;
            V prev = found.value;
            if (!isRed(r.left) && !isRed(r.right)) r = withRed(r, true);
            Node<K, V> nr = delete(r, key);
            if (nr != null) nr = withRed(nr, false);
            root = nr;
            return prev;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) throw new NullPointerException("key");
        return find(root, key) != null;
    }

    public int size() {
        return sz(root);
    }

    public Entry<K, V> entryAt(int index) {
        return select(root, index);
    }

    public int rank(K key) {
        if (key == null) throw new NullPointerException("key");
        Node<K, V> n = root;
        int count = 0;
        while (n != null) {
            int c = order.compare(key, n.key);
            if (c <= 0) {
                n = n.left;
            } else {
                count += sz(n.left) + 1;
                n = n.right;
            }
        }
        return count;
    }

    public Snapshot<K, V> snapshot() {
        return new Snapshot<K, V>(root, order);
    }
}
