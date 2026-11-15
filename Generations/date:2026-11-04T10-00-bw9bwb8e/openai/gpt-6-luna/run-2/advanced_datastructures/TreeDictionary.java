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
            return nodeSize(root);
        }

        public V get(K key) {
            requireNonNull(key);
            Node<K, V> current = root;
            while (current != null) {
                int comparison = order.compare(key, current.key);
                if (comparison == 0) {
                    return current.value;
                }
                current = comparison < 0 ? current.left : current.right;
            }
            return null;
        }

        public Entry<K, V> entryAt(int index) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException();
            }

            Node<K, V> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = nodeSize(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining == leftSize) {
                    return new Entry<K, V>(current.key, current.value);
                } else {
                    remaining -= leftSize + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException();
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

    private final Object monitor = new Object();
    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        requireNonNull(order);
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (monitor) {
            Node<K, V> previous = find(root, key);
            Node<K, V> updated = insert(root, key, value);
            if (updated != null && updated.red) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return previous == null ? null : previous.value;
        }
    }

    public V get(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            Node<K, V> found = find(root, key);
            return found == null ? null : found.value;
        }
    }

    public V remove(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            Node<K, V> found = find(root, key);
            if (found == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, RED);
            }
            updated = delete(updated, key);
            if (updated != null && updated.red) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return found.value;
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            return find(root, key) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return nodeSize(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException();
            }

            Node<K, V> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = nodeSize(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining == leftSize) {
                    return new Entry<K, V>(current.key, current.value);
                } else {
                    remaining -= leftSize + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }
    }

    public int rank(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            int result = 0;
            Node<K, V> current = root;
            while (current != null) {
                int comparison = order.compare(key, current.key);
                if (comparison <= 0) {
                    current = current.left;
                } else {
                    result += nodeSize(current.left) + 1;
                    current = current.right;
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(order, root);
        }
    }

    private Node<K, V> find(Node<K, V> node, K key) {
        Node<K, V> current = node;
        while (current != null) {
            int comparison = order.compare(key, current.key);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private static <K, V> int nodeSize(Node<K, V> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> recolor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, red);
    }

    private static <K, V> Node<K, V> toggleColor(Node<K, V> node) {
        return node == null ? null : recolor(node, !node.red);
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
            updated = new Node<K, V>(node.key, node.value,
                    node.left, insert(node.right, key, value), node.red);
        } else {
            updated = new Node<K, V>(node.key, value, node.left, node.right, node.red);
        }

        if (isRed(updated.right) && !isRed(updated.left)) {
            updated = rotateLeft(updated);
        }
        if (isRed(updated.left) && isRed(updated.left.left)) {
            updated = rotateRight(updated);
        }
        if (isRed(updated.left) && isRed(updated.right)) {
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        Node<K, V> updated = node;

        if (order.compare(key, updated.key) < 0) {
            if (updated.left != null) {
                if (!isRed(updated.left) && !isRed(updated.left.left)) {
                    updated = moveRedLeft(updated);
                }
                updated = new Node<K, V>(updated.key, updated.value,
                        delete(updated.left, key), updated.right, updated.red);
            }
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }

            int comparison = order.compare(key, updated.key);
            if (comparison == 0 && updated.right == null) {
                return null;
            }

            if (updated.right != null) {
                if (!isRed(updated.right) && !isRed(updated.right.left)) {
                    updated = moveRedRight(updated);
                }

                comparison = order.compare(key, updated.key);
                if (comparison == 0) {
                    Node<K, V> successor = minimum(updated.right);
                    updated = new Node<K, V>(successor.key, successor.value,
                            updated.left, deleteMinimum(updated.right), updated.red);
                } else {
                    updated = new Node<K, V>(updated.key, updated.value, updated.left,
                            delete(updated.right, key), updated.red);
                }
            }
        }
        return fixUp(updated);
    }

    private Node<K, V> deleteMinimum(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }

        Node<K, V> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<K, V>(updated.key, updated.value,
                deleteMinimum(updated.left), updated.right, updated.red);
        return fixUp(updated);
    }

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        Node<K, V> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> newLeft = new Node<K, V>(node.key, node.value,
                node.left, right.left, RED);
        return new Node<K, V>(right.key, right.value, newLeft, right.right, node.red);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(node.key, node.value,
                left.right, node.right, RED);
        return new Node<K, V>(left.key, left.value, left.left, newRight, node.red);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value,
                toggleColor(node.left), toggleColor(node.right), !node.red);
    }

    private static <K, V> Node<K, V> fixUp(Node<K, V> node) {
        Node<K, V> updated = node;
        if (isRed(updated.right)) {
            updated = rotateLeft(updated);
        }
        if (isRed(updated.left) && isRed(updated.left.left)) {
            updated = rotateRight(updated);
        }
        if (isRed(updated.left) && isRed(updated.right)) {
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> node) {
        Node<K, V> updated = flipColors(node);
        if (updated.right != null && isRed(updated.right.left)) {
            Node<K, V> newRight = rotateRight(updated.right);
            updated = new Node<K, V>(updated.key, updated.value,
                    updated.left, newRight, updated.red);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <K, V> Node<K, V> moveRedRight(Node<K, V> node) {
        Node<K, V> updated = flipColors(node);
        if (updated.left != null && isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }
}
