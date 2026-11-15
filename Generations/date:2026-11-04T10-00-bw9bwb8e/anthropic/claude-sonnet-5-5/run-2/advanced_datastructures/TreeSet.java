import java.util.*; is deliberately absent; the file uses no imports.

interface Order<T> {
    int compare(T left, T right);
}

interface Snapshot<T> {
    int size();
    T get(int index);
    boolean contains(T item);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}


    // Immutable persistent node (left-leaning red-black tree).
    private static final class Node<T> {
        final T key;
        final boolean red;
        final Node<T> left, right;
        final int size;

        Node(T key, boolean red, Node<T> left, Node<T> right) {
            this.key = key;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + size(left) + size(right);
        }
    }

    private static <T> int size(Node<T> n) {
        return n == null ? 0 : n.size;
    }

    private static <T> boolean isRed(Node<T> n) {
        return n != null && n.red;
    }

    private static <T> Node<T> mk(T key, boolean red, Node<T> l, Node<T> r) {
        return new Node<T>(key, red, l, r);
    }

    private static <T> Node<T> rotateLeft(Node<T> h) {
        Node<T> x = h.right;
        return mk(x.key, h.red, mk(h.key, true, h.left, x.left), x.right);
    }

    private static <T> Node<T> rotateRight(Node<T> h) {
        Node<T> x = h.left;
        return mk(x.key, h.red, x.left, mk(h.key, true, x.right, h.right));
    }

    private static <T> Node<T> flip(Node<T> h) {
        Node<T> l = h.left, r = h.right;
        return mk(h.key, !h.red,
                mk(l.key, !l.red, l.left, l.right),
                mk(r.key, !r.red, r.left, r.right));
    }

    private static <T> Node<T> fixUp(Node<T> h) {
        if (isRed(h.right) && !isRed(h.left)) h = rotateLeft(h);
        if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
        if (isRed(h.left) && isRed(h.right)) h = flip(h);
        return h;
    }

    private static <T> Node<T> moveRedLeft(Node<T> h) {
        h = flip(h);
        if (isRed(h.right.left)) {
            h = mk(h.key, h.red, h.left, rotateRight(h.right));
            h = rotateLeft(h);
            h = flip(h);
        }
        return h;
    }

    private static <T> Node<T> moveRedRight(Node<T> h) {
        h = flip(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
            h = flip(h);
        }
        return h;
    }

    private static <T> Node<T> minNode(Node<T> h) {
        while (h.left != null) h = h.left;
        return h;
    }

    private static <T> Node<T> deleteMin(Node<T> h) {
        if (h.left == null) return null;
        if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
        h = mk(h.key, h.red, deleteMin(h.left), h.right);
        return fixUp(h);
    }

    private static <T> boolean containsIn(Node<T> n, T item, Order<T> order) {
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c == 0) return true;
            n = c < 0 ? n.left : n.right;
        }
        return false;
    }

    private static <T> T getFrom(Node<T> n, int index) {
        if (index < 0 || index >= size(n)) throw new IndexOutOfBoundsException("index " + index);
        while (true) {
            int ls = size(n.left);
            if (index < ls) {
                n = n.left;
            } else if (index == ls) {
                return n.key;
            } else {
                index -= ls + 1;
                n = n.right;
            }
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private volatile Node<T> root; // immutable tree; replaced under lock

    public TreeSet(Order<T> order) {
        if (order == null) throw new NullPointerException("order");
        this.order = order;
    }

    private Node<T> put(Node<T> h, T key) {
        if (h == null) return mk(key, true, null, null);
        int c = order.compare(key, h.key);
        if (c < 0) h = mk(h.key, h.red, put(h.left, key), h.right);
        else h = mk(h.key, h.red, h.left, put(h.right, key));
        return fixUp(h);
    }

    // key is known to be present
    private Node<T> delete(Node<T> h, T key) {
        if (order.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
            h = mk(h.key, h.red, delete(h.left, key), h.right);
        } else {
            if (isRed(h.left)) h = rotateRight(h);
            if (order.compare(key, h.key) == 0 && h.right == null) return null;
            if (!isRed(h.right) && !isRed(h.right.left)) h = moveRedRight(h);
            if (order.compare(key, h.key) == 0) {
                Node<T> m = minNode(h.right);
                h = mk(m.key, h.red, h.left, deleteMin(h.right));
            } else {
                h = mk(h.key, h.red, h.left, delete(h.right, key));
            }
        }
        return fixUp(h);
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (containsIn(r, item, order)) return false;
            Node<T> nr = put(r, item);
            if (nr.red) nr = mk(nr.key, false, nr.left, nr.right);
            root = nr;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("item");
        synchronized (lock) {
            Node<T> r = root;
            if (!containsIn(r, item, order)) return false;
            if (!isRed(r.left) && !isRed(r.right)) r = mk(r.key, true, r.left, r.right);
            Node<T> nr = delete(r, item);
            if (nr != null && nr.red) nr = mk(nr.key, false, nr.left, nr.right);
            root = nr;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("item");
        return containsIn(root, item, order);
    }

    public int size() {
        return size(root);
    }

    public T get(int index) {
        return getFrom(root, index);
    }

    public int rank(T item) {
        if (item == null) throw new NullPointerException("item");
        Node<T> n = root;
        int count = 0;
        while (n != null) {
            int c = order.compare(item, n.key);
            if (c <= 0) {
                n = n.left;
            } else {
                count += size(n.left) + 1;
                n = n.right;
            }
        }
        return count;
    }

    public Snapshot<T> snapshot() {
        return new SnapshotImpl<T>(root, order);
    }

    private static final class SnapshotImpl<T> implements Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        SnapshotImpl(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return TreeSet.size(root);
        }

        public T get(int index) {
            return getFrom(root, index);
        }

        public boolean contains(T item) {
            if (item == null) throw new NullPointerException("item");
            return containsIn(root, item, order);
        }
    }
}
