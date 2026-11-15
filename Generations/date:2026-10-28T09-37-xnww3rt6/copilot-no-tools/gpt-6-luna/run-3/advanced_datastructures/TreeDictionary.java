@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    public interface Order<K> {
        int compare(K left, K right);
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
            Node<K, V> previous = find(root, key, order);
            Node<K, V> updated = putNode(root, key, value, order);
            root = recolor(updated, false);
            return previous == null ? null : previous.value;
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
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }

            Node<K, V> startingRoot = root;
            if (!isRed(startingRoot.left) && !isRed(startingRoot.right)) {
                startingRoot = recolor(startingRoot, true);
            }
            Node<K, V> updated = deleteNode(startingRoot, key, order);
            root = updated == null ? null : recolor(updated, false);
            return previous.value;
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
            return entryAt(root, index);
        }
    }

    public int rank(K key) {
        requireKey(key);
        synchronized (this) {
            return rank(root, key, order);
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (this) {
            return new Snapshot<K, V>(order, root);
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
            return TreeDictionary.entryAt(root, index);
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private final V value;
        private final Node<K, V> left;
        private final Node<K, V> right;
        private final boolean red;
        private final int size;

        private Node(K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
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

    private static <K, V> Node<K, V> node(
            K key, V value, Node<K, V> left, Node<K, V> right, boolean red) {
        return new Node<K, V>(key, value, left, right, red);
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return node(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, K key, Order<K> order) {
        Node<K, V> current = node;
        while (current != null) {
            int comparison = order.compare(key, current.key);
            if (comparison < 0) {
                current = current.left;
            } else if (comparison > 0) {
                current = current.right;
            } else {
                return current;
            }
        }
        return null;
    }

    private static <K, V> Node<K, V> putNode(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return node(key, value, null, null, true);
        }

        int comparison = order.compare(key, node.key);
        Node<K, V> updated;
        if (comparison < 0) {
            updated = node(node.key, node.value,
                    putNode(node.left, key, value, order), node.right, node.red);
        } else if (comparison > 0) {
            updated = node(node.key, node.value,
                    node.left, putNode(node.right, key, value, order), node.red);
        } else {
            updated = node(node.key, value, node.left, node.right, node.red);
        }
        return fixUp(updated);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> root) {
        Node<K, V> right = root.right;
        Node<K, V> movedRoot = node(root.key, root.value, root.left, right.left, true);
        return node(right.key, right.value, movedRoot, right.right, root.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> root) {
        Node<K, V> left = root.left;
        Node<K, V> movedRoot = node(root.key, root.value, left.right, root.right, true);
        return node(left.key, left.value, left.left, movedRoot, root.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> root) {
        Node<K, V> left = recolor(root.left, !root.left.red);
        Node<K, V> right = recolor(root.right, !root.right.red);
        return node(root.key, root.value, left, right, !root.red);
    }

    private static <K, V> Node<K, V> fixUp(Node<K, V> root) {
        Node<K, V> result = root;
        if (isRed(result.right) && !isRed(result.left)) {
            result = rotateLeft(result);
        }
        if (isRed(result.left) && isRed(result.left.left)) {
            result = rotateRight(result);
        }
        if (isRed(result.left) && isRed(result.right)) {
            result = flipColors(result);
        }
        return result;
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> root) {
        Node<K, V> result = flipColors(root);
        if (isRed(result.right.left)) {
            Node<K, V> right = rotateRight(result.right);
            result = node(result.key, result.value, result.left, right, result.red);
            result = rotateLeft(result);
            result = flipColors(result);
        }
        return result;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> root) {
        Node<K, V> result = flipColors(root);
        if (isRed(result.left.left)) {
            result = rotateRight(result);
            result = flipColors(result);
        }
        return result;
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> root) {
        Node<K, V> current = root;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <K, V> Node<K, V> deleteMinimum(Node<K, V> root) {
        if (root.left == null) {
            return null;
        }

        Node<K, V> result = root;
        if (!isRed(result.left) && !isRed(result.left.left)) {
            result = moveRedLeft(result);
        }
        result = node(result.key, result.value,
                deleteMinimum(result.left), result.right, result.red);
        return fixUp(result);
    }

    private static <K, V> Node<K, V> deleteNode(
            Node<K, V> root, K key, Order<K> order) {
        Node<K, V> result = root;
        if (order.compare(key, result.key) < 0) {
            if (result.left != null) {
                if (!isRed(result.left) && !isRed(result.left.left)) {
                    result = moveRedLeft(result);
                }
                result = node(result.key, result.value,
                        deleteNode(result.left, key, order), result.right, result.red);
            }
        } else {
            if (isRed(result.left)) {
                result = rotateRight(result);
            }
            int comparison = order.compare(key, result.key);
            if (comparison == 0 && result.right == null) {
                return null;
            }
            if (result.right != null) {
                if (!isRed(result.right) && !isRed(result.right.left)) {
                    result = moveRedRight(result);
                }
                comparison = order.compare(key, result.key);
                if (comparison == 0) {
                    Node<K, V> successor = minimum(result.right);
                    result = node(successor.key, successor.value, result.left,
                            deleteMinimum(result.right), result.red);
                } else {
                    result = node(result.key, result.value, result.left,
                            deleteNode(result.right, key, order), result.red);
                }
            }
        }
        return fixUp(result);
    }

    private static <K, V> Entry<K, V> entryAt(Node<K, V> root, int index) {
        if (index < 0 || index >= sizeOf(root)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

        Node<K, V> current = root;
        int remaining = index;
        while (current != null) {
            int leftSize = sizeOf(current.left);
            if (remaining < leftSize) {
                current = current.left;
            } else if (remaining == leftSize) {
                return new Entry<K, V>(current.key, current.value);
            } else {
                remaining -= leftSize + 1;
                current = current.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <K, V> int rank(Node<K, V> root, K key, Order<K> order) {
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
