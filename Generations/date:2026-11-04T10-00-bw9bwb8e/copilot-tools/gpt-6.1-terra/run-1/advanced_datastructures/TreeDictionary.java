interface Order<K> {
    int compare(K left, K right);
}

@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Object monitor = new Object();
    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public V put(K key, V value) {
        requireKey(key);
        if (value == null) {
            throw new NullPointerException("value");
        }
        synchronized (monitor) {
            V previous = find(root, key, order);
            if (previous == null && sizeOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("maximum size exceeded");
            }
            Node<K, V> next = insert(root, key, value, order);
            root = color(next, BLACK);
            return previous;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (monitor) {
            return find(root, key, order);
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (monitor) {
            V previous = find(root, key, order);
            if (previous == null) {
                return null;
            }
            Node<K, V> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = color(next, RED);
            }
            next = delete(next, key, order);
            root = next == null ? null : color(next, BLACK);
            return previous;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        synchronized (monitor) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return sizeOf(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        requireKey(key);
        synchronized (monitor) {
            int result = 0;
            Node<K, V> current = root;
            while (current != null) {
                int comparison = order.compare(key, current.key);
                if (comparison < 0) {
                    current = current.left;
                } else if (comparison > 0) {
                    result += sizeOf(current.left) + 1;
                    current = current.right;
                } else {
                    return result + sizeOf(current.left);
                }
            }
            return result;
        }
    }

    // Operations wait only for the monitor, never for capacity or contents.
    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order, monitor);
        }
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
            return sizeOf(root);
        }

        public V get(K key) {
            requireKey(key);
            synchronized (monitor) {
                return find(root, key, order);
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
        private final int size;

        private Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + sizeOf(left) + sizeOf(right);
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static int sizeOf(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> V find(Node<K, V> node, K key, Order<K> order) {
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return node.value;
            }
        }
        return null;
    }

    private static <K, V> Entry<K, V> entryAtNode(Node<K, V> node, int index) {
        if (index < 0 || index >= sizeOf(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (true) {
            int leftSize = sizeOf(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index > leftSize) {
                index -= leftSize + 1;
                node = node.right;
            } else {
                return new Entry<K, V>(node.key, node.value);
            }
        }
    }

    private static <K, V> Node<K, V> color(Node<K, V> node, boolean red) {
        return new Node<K, V>(node.key, node.value, red, node.left, node.right);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> left = new Node<K, V>(
                node.key, node.value, RED, node.left, right.left);
        return new Node<K, V>(right.key, right.value, node.red, left, right.right);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> right = new Node<K, V>(
                node.key, node.value, RED, left.right, node.right);
        return new Node<K, V>(left.key, left.value, node.red, left.left, right);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value, !node.red,
                color(node.left, !node.left.red), color(node.right, !node.right.red));
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, RED, null, null);
        }
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value, node.red,
                    insert(node.left, key, value, order), node.right);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.red,
                    node.left, insert(node.right, key, value, order));
        } else {
            node = new Node<K, V>(node.key, value, node.red, node.left, node.right);
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
            node = new Node<K, V>(node.key, node.value, node.red, node.left, right);
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

    private static <K, V> Node<K, V> deleteMin(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value, node.red,
                deleteMin(node.left), node.right);
        return balance(node);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(node.key, node.value, node.red,
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
                Node<K, V> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                node = new Node<K, V>(successor.key, successor.value, node.red,
                        node.left, deleteMin(node.right));
            } else {
                node = new Node<K, V>(node.key, node.value, node.red,
                        node.left, delete(node.right, key, order));
            }
        }
        return balance(node);
    }
}
