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
            return nodeSize(root);
        }

        public V get(K key) {
            requireNonNull(key, "key");
            Node<K, V> node = find(order, root, key);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            Node<K, V> node = nodeAt(root, index);
            return new Entry<K, V>(node.key, node.value);
        }
    }

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
            this.size = 1 + nodeSize(left) + nodeSize(right);
        }
    }

    private final Object monitor = new Object();
    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        this.order = requireNonNull(order, "order");
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (monitor) {
            Node<K, V> previous = find(order, root, key);
            Node<K, V> updated = insert(order, root, key, value);
            root = recolor(updated, false);
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            Node<K, V> node = find(order, root, key);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            Node<K, V> existing = find(order, root, key);
            if (existing == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(order, updated, key);
            root = recolor(updated, false);
            return existing.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            return find(order, root, key) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return nodeSize(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            Node<K, V> node = nodeAt(root, index);
            return new Entry<K, V>(node.key, node.value);
        }
    }

    public int rank(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            int rank = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    rank += 1 + nodeSize(node.left);
                    node = node.right;
                }
            }
            return rank;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(order, root);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }

    private static int nodeSize(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> find(
            Order<K> order, Node<K, V> root, K key) {
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

    private static <K, V> Node<K, V> nodeAt(Node<K, V> root, int index) {
        if (index < 0 || index >= nodeSize(root)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        Node<K, V> node = root;
        int position = index;
        while (node != null) {
            int leftSize = nodeSize(node.left);
            if (position < leftSize) {
                node = node.left;
            } else if (position == leftSize) {
                return node;
            } else {
                position -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <K, V> Node<K, V> insert(
            Order<K> order, Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, true);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value,
                    insert(order, node.left, key, value), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.left,
                    insert(order, node.right, key, value), node.red);
        } else {
            node = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }

        if (isRed(node.right) && !isRed(node.left)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(node.left.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> delete(
            Order<K> order, Node<K, V> node, K key) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<K, V>(node.key, node.value,
                        delete(order, node.left, key), node.right, node.red);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(key, node.key) == 0 && node.right == null) {
                return null;
            }
            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }
                if (order.compare(key, node.key) == 0) {
                    Node<K, V> successor = minimum(node.right);
                    node = new Node<K, V>(successor.key, successor.value, node.left,
                            deleteMinimum(node.right), node.red);
                } else {
                    node = new Node<K, V>(node.key, node.value, node.left,
                            delete(order, node.right, key), node.red);
                }
            }
        }
        return balance(node);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <K, V> Node<K, V> deleteMinimum(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value,
                deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<K, V> right = rotateRight(node.right);
            node = new Node<K, V>(node.key, node.value, node.left, right, node.red);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> node) {
        node = flipColors(node);
        if (node.left != null && isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> balance(Node<K, V> node) {
        if (isRed(node.right)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(node.left.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> left = new Node<K, V>(node.key, node.value, node.left,
                right.left, true);
        return new Node<K, V>(right.key, right.value, left, right.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> right = new Node<K, V>(node.key, node.value, left.right,
                node.right, true);
        return new Node<K, V>(left.key, left.value, left.left, right, node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value,
                recolor(node.left, !isRed(node.left)),
                recolor(node.right, !isRed(node.right)),
                !node.red);
    }
}
