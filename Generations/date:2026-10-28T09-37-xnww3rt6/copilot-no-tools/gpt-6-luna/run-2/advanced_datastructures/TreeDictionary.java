@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    public interface Order<K> {
        int compare(K left, K right);
    }

    public static final class Entry<K, V> {
        private final K key;
        private final V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K key() {
            return key;
        }

        public V value() {
            return value;
        }
    }

    public static final class Snapshot<K, V> {
        private final Order<K> order;
        private final Node<K, V> root;

        private Snapshot(Order<K> order, Node<K, V> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return sizeOf(root);
        }

        public V get(K key) {
            requireNonNull(key, "key");
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<K, V> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final Node<K, V> left;
        private final Node<K, V> right;
        private final boolean red;
        private final int size;

        private Node(K key, V value, Node<K, V> left, Node<K, V> right,
                     boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + sizeOf(left) + sizeOf(right);
        }
    }

    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        this.order = requireNonNull(order, "order");
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            V previous = existing == null ? null : existing.value;
            Node<K, V> updated = insert(root, key, value, order);
            root = recolor(updated, false);
            return previous;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
                return null;
            }

            V previous = existing.value;
            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, key, order);
            if (updated != null) {
                updated = recolor(updated, false);
            }
            root = updated;
            return previous;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (this) {
            return sizeOf(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (this) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<K, V> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(K key) {
        requireNonNull(key, "key");

        synchronized (this) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += sizeOf(node.left) + 1;
                    node = node.right;
                } else {
                    return result + sizeOf(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (this) {
            return new Snapshot<K, V>(order, root);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }

    private static int sizeOf(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> node(
            K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
        return new Node<K, V>(key, value, left, right, red);
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return node(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> root, K key, Order<K> order) {
        Node<K, V> node = root;
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return node;
            }
        }
        return null;
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> h, K key, V value, Order<K> order) {
        if (h == null) {
            return node(key, value, null, null, true);
        }

        int comparison = order.compare(key, h.key);
        if (comparison < 0) {
            h = node(h.key, h.value, insert(h.left, key, value, order),
                    h.right, h.red);
        } else if (comparison > 0) {
            h = node(h.key, h.value, h.left,
                    insert(h.right, key, value, order), h.red);
        } else {
            h = node(h.key, value, h.left, h.right, h.red);
        }

        if (isRed(h.right) && !isRed(h.left)) {
            h = rotateLeft(h);
        }
        if (isRed(h.left) && isRed(h.left.left)) {
            h = rotateRight(h);
        }
        if (isRed(h.left) && isRed(h.right)) {
            h = flipColors(h);
        }
        return h;
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> h, K key, Order<K> order) {
        if (order.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) {
                h = moveRedLeft(h);
            }
            h = node(h.key, h.value, delete(h.left, key, order), h.right, h.red);
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }
            if (order.compare(key, h.key) == 0 && h.right == null) {
                return null;
            }
            if (!isRed(h.right) && !isRed(h.right.left)) {
                h = moveRedRight(h);
            }
            if (order.compare(key, h.key) == 0) {
                Node<K, V> minimum = minimum(h.right);
                h = node(minimum.key, minimum.value, h.left,
                        deleteMinimum(h.right), h.red);
            } else {
                h = node(h.key, h.value, h.left,
                        delete(h.right, key, order), h.red);
            }
        }
        return balance(h);
    }

    private static <K, V> Node<K, V> deleteMinimum(Node<K, V> h) {
        if (h.left == null) {
            return null;
        }
        if (!isRed(h.left) && !isRed(h.left.left)) {
            h = moveRedLeft(h);
        }
        h = node(h.key, h.value, deleteMinimum(h.left), h.right, h.red);
        return balance(h);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> h) {
        Node<K, V> node = h;
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> h) {
        h = flipColors(h);
        if (isRed(h.right.left)) {
            Node<K, V> right = rotateRight(h.right);
            h = node(h.key, h.value, h.left, right, h.red);
            h = rotateLeft(h);
            h = flipColors(h);
        }
        return h;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> h) {
        h = flipColors(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
            h = flipColors(h);
        }
        return h;
    }

    private static <K, V> Node<K, V> balance(Node<K, V> h) {
        if (isRed(h.right)) {
            h = rotateLeft(h);
        }
        if (isRed(h.left) && isRed(h.left.left)) {
            h = rotateRight(h);
        }
        if (isRed(h.left) && isRed(h.right)) {
            h = flipColors(h);
        }
        return h;
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> h) {
        Node<K, V> x = h.right;
        Node<K, V> newLeft = node(h.key, h.value, h.left, x.left, true);
        return node(x.key, x.value, newLeft, x.right, h.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> h) {
        Node<K, V> x = h.left;
        Node<K, V> newRight = node(h.key, h.value, x.right, h.right, true);
        return node(x.key, x.value, x.left, newRight, h.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> h) {
        Node<K, V> left = recolor(h.left, !h.left.red);
        Node<K, V> right = recolor(h.right, !h.right.red);
        return node(h.key, h.value, left, right, !h.red);
    }
}
