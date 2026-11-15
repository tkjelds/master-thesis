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
            Node<K, V> previous = find(root, key, order);
            root = putNode(root, key, value, order);
            if (root.red) {
                root = recolor(root, BLACK);
            }
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (monitor) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }

            Node<K, V> start = root;
            if (!isRed(start.left) && !isRed(start.right)) {
                start = recolor(start, RED);
            }
            root = deleteNode(start, key, order);
            if (root != null && root.red) {
                root = recolor(root, BLACK);
            }
            return previous.value;
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
            checkIndex(index, sizeOf(root));
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        requireKey(key);
        synchronized (monitor) {
            int rank = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    rank += sizeOf(node.left) + 1;
                    node = node.right;
                }
            }
            return rank;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order);
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

        private Snapshot(Node<K, V> root, Order<K> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return sizeOf(root);
        }

        public V get(K key) {
            requireKey(key);
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            checkIndex(index, sizeOf(root));
            return entryAtNode(root, index);
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final Node<K, V> left;
        private final Node<K, V> right;
        private final boolean red;
        private final int count;

        private Node(K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.count = sizeOf(left) + sizeOf(right) + 1;
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static int sizeOf(Node<?, ?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, K key, Order<K> order) {
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
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index == leftSize) {
                return new Entry<K, V>(node.key, node.value);
            } else {
                index -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException();
    }

    private static <K, V> Node<K, V> putNode(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, RED);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(
                    node.key, node.value, putNode(node.left, key, value, order), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<K, V>(
                    node.key, node.value, node.left, putNode(node.right, key, value, order), node.red);
        } else {
            node = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }
        return balance(node);
    }

    private static <K, V> Node<K, V> deleteNode(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<K, V>(
                        node.key, node.value, deleteNode(node.left, key, order), node.right, node.red);
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
                    node = new Node<K, V>(
                            successor.key,
                            successor.value,
                            node.left,
                            deleteMinimum(node.right),
                            node.red);
                } else {
                    node = new Node<K, V>(
                            node.key,
                            node.value,
                            node.left,
                            deleteNode(node.right, key, order),
                            node.red);
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
        node = new Node<K, V>(
                node.key, node.value, deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <K, V> Node<K, V> balance(Node<K, V> node) {
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

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(
                node.key,
                node.value,
                node.left == null ? null : recolor(node.left, !node.left.red),
                node.right == null ? null : recolor(node.right, !node.right.red),
                !node.red);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right;
        Node<K, V> left = new Node<K, V>(
                node.key, node.value, node.left, pivot.left, RED);
        return new Node<K, V>(
                pivot.key, pivot.value, left, pivot.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left;
        Node<K, V> right = new Node<K, V>(
                node.key, node.value, pivot.right, node.right, RED);
        return new Node<K, V>(
                pivot.key, pivot.value, pivot.left, right, node.red);
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }
}
