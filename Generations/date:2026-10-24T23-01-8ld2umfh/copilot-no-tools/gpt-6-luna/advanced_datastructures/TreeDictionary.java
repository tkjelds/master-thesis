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
            if (key == null) {
                throw new NullPointerException();
            }
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            Node<K, V> node = nodeAt(root, index);
            return new Entry<K, V>(node.key, node.value);
        }
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int size;

        Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + nodeSize(left) + nodeSize(right);
        }
    }

    private final Object monitor = new Object();
    private final Order<K> order;
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public V put(K key, V value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null && nodeSize(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeDictionary is full");
            }
            V previous = existing == null ? null : existing.value;
            Node<K, V> updated = insert(root, key, value, order);
            root = withColor(updated, BLACK);
            return previous;
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

            V previous = existing.value;
            Node<K, V> start = root;
            if (!isRed(start.left) && !isRed(start.right)) {
                start = withColor(start, RED);
            }
            Node<K, V> updated = delete(start, key, order);
            root = updated == null ? null : withColor(updated, BLACK);
            return previous;
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
            return nodeSize(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            Node<K, V> node = nodeAt(root, index);
            return new Entry<K, V>(node.key, node.value);
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
        synchronized (monitor) {
            return new Snapshot<K, V>(order, root);
        }
    }

    private static <K, V> int nodeSize(Node<K, V> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red == RED;
    }

    private static <K, V> Node<K, V> withColor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, red, node.left, node.right);
    }

    private static <K, V> Node<K, V> find(Node<K, V> root, K key, Order<K> order) {
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

    private static <K, V> Node<K, V> nodeAt(Node<K, V> root, int index) {
        if (index < 0 || index >= nodeSize(root)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        Node<K, V> node = root;
        int position = index;
        while (node != null) {
            int leftSize = nodeSize(node.left);
            if (position < leftSize) {
                node = node.left;
            } else if (position == leftSize) {
                return node;
            } else {
                position -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, RED, null, null);
        }

        int comparison = order.compare(key, node.key);
        Node<K, V> updated;
        if (comparison < 0) {
            updated = new Node<K, V>(
                    node.key, node.value, node.red,
                    insert(node.left, key, value, order), node.right);
        } else if (comparison > 0) {
            updated = new Node<K, V>(
                    node.key, node.value, node.red,
                    node.left, insert(node.right, key, value, order));
        } else {
            updated = new Node<K, V>(node.key, value, node.red, node.left, node.right);
        }
        return balance(updated);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<K, V>(
                        node.key, node.value, node.red,
                        delete(node.left, key, order), node.right);
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
                            successor.key, successor.value, node.red, node.left,
                            deleteMinimum(node.right));
                } else {
                    node = new Node<K, V>(
                            node.key, node.value, node.red, node.left,
                            delete(node.right, key, order));
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
                node.key, node.value, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
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
        Node<K, V> leftChild = new Node<K, V>(
                node.key, node.value, RED, node.left, right.left);
        return new Node<K, V>(
                right.key, right.value, node.red, leftChild, right.right);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> rightChild = new Node<K, V>(
                node.key, node.value, RED, left.right, node.right);
        return new Node<K, V>(
                left.key, left.value, node.red, left.left, rightChild);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        Node<K, V> left = node.left == null ? null : withColor(node.left, !node.left.red);
        Node<K, V> right = node.right == null ? null : withColor(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, !node.red, left, right);
    }

    private static <K, V> Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<K, V> right = rotateRight(node.right);
            node = new Node<K, V>(node.key, node.value, node.red, node.left, right);
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
}
