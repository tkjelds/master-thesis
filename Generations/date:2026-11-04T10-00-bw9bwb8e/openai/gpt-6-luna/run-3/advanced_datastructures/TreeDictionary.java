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
            requireNonNull(key);
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            int treeSize = count(root);
            if (index < 0 || index >= treeSize) {
                throw new IndexOutOfBoundsException();
            }

            Node<K, V> node = root;
            int position = index;
            while (node != null) {
                int leftSize = count(node.left);
                if (position < leftSize) {
                    node = node.left;
                } else if (position == leftSize) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    position -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
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
            this.size = 1 + count(left) + count(right);
        }
    }

    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        requireNonNull(order);
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            Node<K, V> newRoot = insert(root, key, value);
            root = recolor(newRoot, false);
            return existing == null ? null : existing.value;
        }
    }

    public V get(K key) {
        requireNonNull(key);
        synchronized (this) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key);
        synchronized (this) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
                return null;
            }

            Node<K, V> newRoot = root;
            if (!isRed(newRoot.left) && !isRed(newRoot.right)) {
                newRoot = recolor(newRoot, true);
            }
            newRoot = delete(newRoot, key);
            root = newRoot == null ? null : recolor(newRoot, false);
            return existing.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
        synchronized (this) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (this) {
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (this) {
            int treeSize = count(root);
            if (index < 0 || index >= treeSize) {
                throw new IndexOutOfBoundsException();
            }

            Node<K, V> node = root;
            int position = index;
            while (node != null) {
                int leftSize = count(node.left);
                if (position < leftSize) {
                    node = node.left;
                } else if (position == leftSize) {
                    return new Entry<K, V>(node.key, node.value);
                } else {
                    position -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }
    }

    public int rank(K key) {
        requireNonNull(key);
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

    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, true);
        }

        int comparison = order.compare(key, node.key);
        Node<K, V> result;
        if (comparison < 0) {
            result = new Node<K, V>(node.key, node.value,
                    insert(node.left, key, value), node.right, node.red);
        } else if (comparison > 0) {
            result = new Node<K, V>(node.key, node.value,
                    node.left, insert(node.right, key, value), node.red);
        } else {
            result = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }
        return fixUp(result);
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
                node = new Node<K, V>(successor.key, successor.value,
                        node.left, deleteMin(node.right), node.red);
            } else {
                node = new Node<K, V>(node.key, node.value,
                        node.left, delete(node.right, key), node.red);
            }
        }
        return fixUp(node);
    }

    private Node<K, V> deleteMin(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }

        if (!isRed(node.left) && !isRed(leftOf(node.left))) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value,
                deleteMin(node.left), node.right, node.red);
        return fixUp(node);
    }

    private Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.right))) {
            Node<K, V> newRight = rotateRight(node.right);
            node = new Node<K, V>(node.key, node.value,
                    node.left, newRight, node.red);
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

    private Node<K, V> fixUp(Node<K, V> node) {
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

    private Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> newLeft = new Node<K, V>(node.key, node.value,
                node.left, right.left, true);
        return new Node<K, V>(right.key, right.value, newLeft,
                right.right, node.red);
    }

    private Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(node.key, node.value,
                left.right, node.right, true);
        return new Node<K, V>(left.key, left.value, left.left,
                newRight, node.red);
    }

    private Node<K, V> flipColors(Node<K, V> node) {
        Node<K, V> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<K, V> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, left, right, !node.red);
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> leftOf(Node<K, V> node) {
        return node == null ? null : node.left;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.size;
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, K key, Order<K> order) {
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

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }
}
