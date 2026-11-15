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
            requireNonNull(key);
            synchronized (monitor) {
                Node<K, V> node = find(root, key, order);
                return node == null ? null : node.value;
            }
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

    private final Order<K> order;
    private final Object monitor = new Object();
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        this.order = requireNonNull(order);
    }

    // Operations wait only for the monitor; absence never causes a wait.
    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (monitor) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null && count(root) == 2147483647) {
                throw new IllegalStateException("TreeDictionary is too large");
            }
            Node<K, V> next = insert(root, key, value, order);
            root = color(next, false);
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }
            Node<K, V> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = color(next, true);
            }
            next = delete(next, key, order);
            root = next == null ? null : color(next, false);
            return previous.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
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
        requireNonNull(key);
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

    private static <T> T requireNonNull(T value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }

    private static <K, V> int count(Node<K, V> node) {
        return node == null ? 0 : node.count;
    }

    private static <K, V> boolean isRed(Node<K, V> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> color(Node<K, V> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> node, K key, Order<K> order) {
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <K, V> Entry<K, V> entryAtNode(Node<K, V> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException();
        }
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

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> left = new Node<K, V>(
                node.key, node.value, node.left, right.left, true);
        return new Node<K, V>(right.key, right.value, left, right.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> right = new Node<K, V>(
                node.key, node.value, left.right, node.right, true);
        return new Node<K, V>(left.key, left.value, left.left, right, node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        Node<K, V> left = node.left == null ? null : color(node.left, !node.left.red);
        Node<K, V> right = node.right == null ? null : color(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, left, right, !node.red);
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

    private static <K, V> Node<K, V> deleteMin(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value,
                deleteMin(node.left), node.right, node.red);
        return balance(node);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(node.key, node.value,
                    delete(node.left, key, order), node.right, node.red);
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
                Node<K, V> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                node = new Node<K, V>(successor.key, successor.value,
                        node.left, deleteMin(node.right), node.red);
            } else {
                node = new Node<K, V>(node.key, node.value, node.left,
                        delete(node.right, key, order), node.red);
            }
        }
        return balance(node);
    }
}
