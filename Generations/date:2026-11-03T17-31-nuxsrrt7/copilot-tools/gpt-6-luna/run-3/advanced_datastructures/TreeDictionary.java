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
            requireKey(key);
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            int count = sizeOf(root);
            if (index < 0 || index >= count) {
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

        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            if (root != null && root.size == Integer.MAX_VALUE && existing == null) {
                throw new IllegalStateException("TreeDictionary is full");
            }

            Node<K, V> updated = insert(root, key, value, order);
            if (updated.red) {
                updated = withColor(updated, false);
            }
            root = updated;
            return existing == null ? null : existing.value;
        }
    }

    public V get(K key) {
        requireKey(key);
        synchronized (this) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireKey(key);
        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, true);
            }
            updated = delete(updated, key, order);
            if (updated != null && updated.red) {
                updated = withColor(updated, false);
            }
            root = updated;
            return existing.value;
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
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
            int count = sizeOf(root);
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return entryAt(root, index);
        }
    }

    public int rank(K key) {
        requireKey(key);
        synchronized (this) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += sizeOf(node.left) + 1;
                    node = node.right;
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

    private static <K, V> Node<K, V> find(Node<K, V> node, K key,
                                           Order<K> order) {
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

    private static <K, V> Entry<K, V> entryAt(Node<K, V> root, int index) {
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

    private static <K, V> Node<K, V> insert(Node<K, V> node, K key, V value,
                                              Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, true);
        }

        int comparison = order.compare(key, node.key);
        Node<K, V> updated;
        if (comparison < 0) {
            updated = new Node<K, V>(node.key, node.value,
                    insert(node.left, key, value, order), node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<K, V>(node.key, node.value, node.left,
                    insert(node.right, key, value, order), node.red);
        } else {
            updated = new Node<K, V>(node.key, value, node.left, node.right,
                    node.red);
        }
        return fixUp(updated);
    }

    private static <K, V> Node<K, V> delete(Node<K, V> node, K key,
                                              Order<K> order) {
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
                    node = new Node<K, V>(successor.key, successor.value,
                            node.left, deleteMinimum(node.right), node.red);
                } else {
                    node = new Node<K, V>(node.key, node.value, node.left,
                            delete(node.right, key, order), node.red);
                }
            }
        }
        return fixUp(node);
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
        return fixUp(node);
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
            node = new Node<K, V>(node.key, node.value, node.left, right,
                    node.red);
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

    private static <K, V> Node<K, V> fixUp(Node<K, V> node) {
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

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> newLeft = new Node<K, V>(node.key, node.value, node.left,
                right.left, true);
        return new Node<K, V>(right.key, right.value, newLeft, right.right,
                node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(node.key, node.value, left.right,
                node.right, true);
        return new Node<K, V>(left.key, left.value, left.left, newRight,
                node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        Node<K, V> left = node.left == null ? null : withColor(node.left, !node.left.red);
        Node<K, V> right = node.right == null ? null : withColor(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, left, right, !node.red);
    }

    private static <K, V> Node<K, V> withColor(Node<K, V> node, boolean red) {
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }
}
