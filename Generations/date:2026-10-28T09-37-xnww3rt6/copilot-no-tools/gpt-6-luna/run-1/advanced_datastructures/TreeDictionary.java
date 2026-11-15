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
        private final Node<K, V> root;
        private final Order<K> order;

        private Snapshot(Node<K, V> root, Order<K> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return count(root);
        }

        public V get(K key) {
            requireNonNull(key, "key");
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            return entryAtNode(root, index);
        }
    }

    private static final class Node<K, V> {
        final K key;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;
        final boolean red;
        final int count;

        Node(K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.count = 1 + count(left) + count(right);
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
            Node<K, V> previous = find(root, key, order);
            Node<K, V> updated = insert(root, key, value, order);
            root = recolor(updated, false);
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, key, order);
            root = updated == null ? null : recolor(updated, false);
            return previous.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key, "key");
        synchronized (monitor) {
            return rankOf(root, key, order);
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.count;
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
            Node<K, V> node, K key, Order<K> order) {
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
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, true);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value,
                    insert(node.left, key, value, order), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.left,
                    insert(node.right, key, value, order), node.red);
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
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<K, V>(node.key, node.value,
                        delete(node.left, key, order), node.right, node.red);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            int comparison = order.compare(key, node.key);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }

                comparison = order.compare(key, node.key);
                if (comparison == 0) {
                    Node<K, V> successor = minimum(node.right);
                    node = new Node<K, V>(successor.key, successor.value, node.left,
                            deleteMinimum(node.right), node.red);
                } else {
                    node = new Node<K, V>(node.key, node.value, node.left,
                            delete(node.right, key, order), node.red);
                }
            }
        }
        return balance(node);
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

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
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
                recolor(node.right, !isRed(node.right)), !node.red);
    }

    private static <K, V> int rankOf(
            Node<K, V> node, K key, Order<K> order) {
        int rank = 0;
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                rank += count(node.left) + 1;
                node = node.right;
            } else {
                return rank + count(node.left);
            }
        }
        return rank;
    }

    private static <K, V> Entry<K, V> entryAtNode(Node<K, V> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

        while (node != null) {
            int leftCount = count(node.left);
            if (index < leftCount) {
                node = node.left;
            } else if (index == leftCount) {
                return new Entry<K, V>(node.key, node.value);
            } else {
                index -= leftCount + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index out of range");
    }
}
