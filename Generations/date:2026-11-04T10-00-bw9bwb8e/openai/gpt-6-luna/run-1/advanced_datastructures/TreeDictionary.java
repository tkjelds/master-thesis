@TreeDictionary.ThreadSafe
public class TreeDictionary<K, V> {
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
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            return entryAtRoot(root, index);
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

    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        this.order = requireNonNull(order, "order");
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");

        synchronized (this) {
            Node<K, V> previous = find(root, key, order);
            Node<K, V> updated = putNode(root, key, value, order);
            if (updated != null && updated.red) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, RED);
            }
            updated = deleteNode(updated, key, order);
            if (updated != null && updated.red) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return previous.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (this) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (this) {
            return nodeSize(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (this) {
            return entryAtRoot(root, index);
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
                    result += nodeSize(node.left) + 1;
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

    private static <K, V> Node<K, V> putNode(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, RED);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value,
                    putNode(node.left, key, value, order), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.left,
                    putNode(node.right, key, value, order), node.red);
        } else {
            node = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }
        return fixUp(node);
    }

    private static <K, V> Node<K, V> deleteNode(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<K, V>(node.key, node.value,
                        deleteNode(node.left, key, order), node.right, node.red);
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
                            deleteNode(node.right, key, order), node.red);
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
            Node<K, V> newRight = rotateRight(node.right);
            node = new Node<K, V>(node.key, node.value, node.left,
                    newRight, node.red);
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
        Node<K, V> newLeft = new Node<K, V>(node.key, node.value,
                node.left, right.left, RED);
        return new Node<K, V>(right.key, right.value, newLeft,
                right.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(node.key, node.value,
                left.right, node.right, RED);
        return new Node<K, V>(left.key, left.value, left.left,
                newRight, node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        Node<K, V> left = node.left == null
                ? null : recolor(node.left, !node.left.red);
        Node<K, V> right = node.right == null
                ? null : recolor(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, left, right, !node.red);
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        return new Node<K, V>(node.key, node.value,
                node.left, node.right, red);
    }

    private static <K, V> Entry<K, V> entryAtRoot(
            Node<K, V> root, int index) {
        if (index < 0 || index >= nodeSize(root)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

        Node<K, V> node = root;
        int remaining = index;
        while (node != null) {
            int leftSize = nodeSize(node.left);
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
