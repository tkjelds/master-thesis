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
            return count(root);
        }

        public V get(K key) {
            requireNonNull(key, "key");
            Node<K, V> node = find(root, order, key);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            checkIndex(index, count(root));
            Node<K, V> node = root;
            int remaining = index;
            while (node != null) {
                int leftCount = count(node.left);
                if (remaining < leftCount) {
                    node = node.left;
                } else if (remaining == leftCount) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    remaining -= leftCount + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

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
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        this.order = requireNonNull(order, "order");
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (this) {
            Node<K, V> existing = find(root, order, key);
            if (existing == null && count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("maximum mapping count reached");
            }
            V previous = existing == null ? null : existing.value;
            Node<K, V> updated = insert(root, key, value);
            root = paint(updated, BLACK);
            return previous;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            Node<K, V> node = find(root, order, key);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            Node<K, V> existing = find(root, order, key);
            if (existing == null) {
                return null;
            }
            V previous = existing.value;
            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = paint(updated, RED);
            }
            updated = delete(updated, key);
            root = updated == null ? null : paint(updated, BLACK);
            return previous;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            return find(root, order, key) != null;
        }
    }

    public int size() {
        synchronized (this) {
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (this) {
            checkIndex(index, count(root));
            Node<K, V> node = root;
            int remaining = index;
            while (node != null) {
                int leftCount = count(node.left);
                if (remaining < leftCount) {
                    node = node.left;
                } else if (remaining == leftCount) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    remaining -= leftCount + 1;
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
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += count(node.left) + 1;
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

    private static <K, V> Node<K, V> find(Node<K, V> root, Order<K> order, K key) {
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

    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, RED);
        }
        int comparison = order.compare(key, node.key);
        Node<K, V> updated;
        if (comparison < 0) {
            updated = new Node<K, V>(node.key, node.value,
                    insert(node.left, key, value), node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<K, V>(node.key, node.value, node.left,
                    insert(node.right, key, value), node.red);
        } else {
            updated = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }
        return balance(updated);
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(leftOf(node.left))) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(node.key, node.value,
                    delete(node.left, key), node.right, node.red);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            int comparison = order.compare(key, node.key);
            if (comparison == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !isRed(leftOf(node.right))) {
                node = moveRedRight(node);
            }
            comparison = order.compare(key, node.key);
            if (comparison == 0) {
                Node<K, V> successor = minimum(node.right);
                node = new Node<K, V>(successor.key, successor.value, node.left,
                        deleteMinimum(node.right), node.red);
            } else {
                node = new Node<K, V>(node.key, node.value, node.left,
                        delete(node.right, key), node.red);
            }
        }
        return balance(node);
    }

    private Node<K, V> deleteMinimum(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(leftOf(node.left))) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value,
                deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.right))) {
            Node<K, V> right = rotateRight(node.right);
            node = new Node<K, V>(node.key, node.value, node.left, right, node.red);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<K, V> moveRedRight(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.left))) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <K, V> Node<K, V> balance(Node<K, V> node) {
        if (isRed(node.right) && !isRed(node.left)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(leftOf(node.left))) {
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
                right.left, RED);
        return new Node<K, V>(right.key, right.value, left, right.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> right = new Node<K, V>(node.key, node.value, left.right,
                node.right, RED);
        return new Node<K, V>(left.key, left.value, left.left, right, node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value,
                paint(node.left, !node.left.red),
                paint(node.right, !node.right.red), !node.red);
    }

    private static <K, V> Node<K, V> paint(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <K, V> Node<K, V> leftOf(Node<K, V> node) {
        return node == null ? null : node.left;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red == RED;
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.count;
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }
}
