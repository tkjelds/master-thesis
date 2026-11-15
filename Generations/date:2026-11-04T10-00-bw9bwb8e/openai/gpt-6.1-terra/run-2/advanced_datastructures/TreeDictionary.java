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
        private final Object lock;

        private Snapshot(Node<K, V> root, Order<K> order, Object lock) {
            this.root = root;
            this.order = order;
            this.lock = lock;
        }

        public int size() {
            return count(root);
        }

        public V get(K key) {
            requireNonNull(key);
            synchronized (lock) {
                Node<K, V> node = find(root, key, order);
                return node == null ? null : node.value;
            }
        }

        public Entry<K, V> entryAt(int index) {
            return entryAtNode(root, index);
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final boolean red;
        private final Node<K, V> left;
        private final Node<K, V> right;
        private final int count;

        private Node(K key, V value, boolean red,
                     Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
        }
    }

    private final Order<K> order;
    private final Object lock = new Object();
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        requireNonNull(order);
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (lock) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null && count(root) == 2147483647) {
                throw new IllegalStateException("Maximum size exceeded");
            }

            Node<K, V> updated = insert(root, key, value, order);
            root = withColor(updated, false);
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key);
        synchronized (lock) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, true);
            }
            updated = delete(updated, key, order);
            if (updated != null) {
                updated = withColor(updated, false);
            }
            root = updated;
            return previous.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
        synchronized (lock) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key);
        synchronized (lock) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += 1 + count(node.left);
                    node = node.right;
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new Snapshot<K, V>(root, order, lock);
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
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

    private static <K, V> Entry<K, V> entryAtNode(
            Node<K, V> root, int index) {
        if (index < 0 || index >= count(root)) {
            throw new IndexOutOfBoundsException();
        }

        Node<K, V> node = root;
        while (true) {
            int leftCount = count(node.left);
            if (index < leftCount) {
                node = node.left;
            } else if (index > leftCount) {
                index -= leftCount + 1;
                node = node.right;
            } else {
                return new Entry<K, V>(node.key, node.value);
            }
        }
    }

    private static <K, V> Node<K, V> withColor(
            Node<K, V> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<K, V>(
                node.key, node.value, red, node.left, node.right);
    }

    private static <K, V> Node<K, V> withLeft(
            Node<K, V> node, Node<K, V> left) {
        return new Node<K, V>(
                node.key, node.value, node.red, left, node.right);
    }

    private static <K, V> Node<K, V> withRight(
            Node<K, V> node, Node<K, V> right) {
        return new Node<K, V>(
                node.key, node.value, node.red, node.left, right);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> newLeft = new Node<K, V>(
                node.key, node.value, true, node.left, right.left);
        return new Node<K, V>(
                right.key, right.value, node.red, newLeft, right.right);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(
                node.key, node.value, true, left.right, node.right);
        return new Node<K, V>(
                left.key, left.value, node.red, left.left, newRight);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(
                node.key, node.value, !node.red,
                withColor(node.left, !node.left.red),
                withColor(node.right, !node.right.red));
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, true, null, null);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = withLeft(node, insert(node.left, key, value, order));
        } else if (comparison > 0) {
            node = withRight(node, insert(node.right, key, value, order));
        } else {
            node = new Node<K, V>(
                    node.key, value, node.red, node.left, node.right);
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

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = withRight(node, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
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
        node = withLeft(node, deleteMinimum(node.left));
        return balance(node);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = withLeft(node, delete(node.left, key, order));
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(key, node.key) == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !isRed(node.right.left)) {
                node = moveRedRight(node);
            }
            if (order.compare(key, node.key) == 0) {
                Node<K, V> successor = minimum(node.right);
                node = new Node<K, V>(
                        successor.key, successor.value, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = withRight(node, delete(node.right, key, order));
            }
        }
        return balance(node);
    }
}
