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
        private final Object monitor;

        private Snapshot(Node<K, V> root, Order<K> order, Object monitor) {
            this.root = root;
            this.order = order;
            this.monitor = monitor;
        }

        public int size() {
            return count(root);
        }

        public V get(K key) {
            if (key == null) {
                throw new NullPointerException();
            }
            synchronized (monitor) {
                Node<K, V> node = find(root, key, order);
                return node == null ? null : node.value;
            }
        }

        public Entry<K, V> entryAt(int index) {
            return entryAtRoot(root, index);
        }
    }

    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int count;

        Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
        }
    }

    private final Order<K> order;
    private final Object monitor = new Object();
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    // Operations never wait for a key or capacity; only monitor acquisition
    // can block.
    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null && count(root) == 2147483647) {
                throw new IllegalStateException("Dictionary is too large");
            }
            Node<K, V> updated = insert(root, key, value, order);
            root = withColor(updated, false);
            return existing == null ? null : existing.value;
        }
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
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
            return existing.value;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
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
            return entryAtRoot(root, index);
        }
    }

    public int rank(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += 1 + count(node.left);
                    node = node.right;
                } else {
                    return result + count(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order, monitor);
        }
    }

    private static <K, V> int count(Node<K, V> node) {
        return node == null ? 0 : node.count;
    }

    private static <K, V> boolean isRed(Node<K, V> node) {
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

    private static <K, V> Entry<K, V> entryAtRoot(
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
        return new Node<K, V>(node.key, node.value, red, node.left, node.right);
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
        Node<K, V> left = withColor(node.left, !node.left.red);
        Node<K, V> right = withColor(node.right, !node.right.red);
        return new Node<K, V>(
                node.key, node.value, !node.red, left, right);
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, true, null, null);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(
                    node.key, node.value, node.red,
                    insert(node.left, key, value, order), node.right);
        } else if (comparison > 0) {
            node = new Node<K, V>(
                    node.key, node.value, node.red,
                    node.left, insert(node.right, key, value, order));
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
            Node<K, V> right = rotateRight(node.right);
            node = new Node<K, V>(
                    node.key, node.value, node.red, node.left, right);
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
        node = new Node<K, V>(
                node.key, node.value, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(
                    node.key, node.value, node.red,
                    delete(node.left, key, order), node.right);
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
                node = new Node<K, V>(
                        node.key, node.value, node.red,
                        node.left, delete(node.right, key, order));
            }
        }
        return balance(node);
    }
}
