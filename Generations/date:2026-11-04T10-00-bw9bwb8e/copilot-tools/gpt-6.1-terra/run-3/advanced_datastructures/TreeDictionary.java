interface Order<K> {
    int compare(K left, K right);
}

@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<K, V> {
        final K key;
        final V value;
        final Node<K, V> left;
        final Node<K, V> right;
        final boolean color;
        final int count;

        Node(K key, V value, Node<K, V> left, Node<K, V> right,
             boolean color) {
            this.key = key;
            this.value = value;
            this.left = left;
            this.right = right;
            this.color = color;
            this.count = 1 + count(left) + count(right);
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
            return entryAtRoot(root, index);
        }
    }

    private final Order<K> order;
    private final Object monitor = new Object();
    private Node<K, V> root;

    public TreeDictionary(Order<K> order) {
        requireNonNull(order);
        this.order = order;
    }

    public V put(K key, V value) {
        requireNonNull(key);
        requireNonNull(value);
        synchronized (monitor) {
            Node<K, V> old = find(root, key, order);
            if (old == null && count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeDictionary is full");
            }
            Node<K, V> updated = insert(root, key, value, order);
            root = withColor(updated, BLACK);
            return old == null ? null : old.value;
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
            Node<K, V> old = find(root, key, order);
            if (old == null) {
                return null;
            }
            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, RED);
            }
            updated = delete(updated, key, order);
            root = updated == null ? null : withColor(updated, BLACK);
            return old.value;
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
            return entryAtRoot(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            int rank = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    rank += 1 + count(node.left);
                    node = node.right;
                } else {
                    return rank + count(node.left);
                }
            }
            return rank;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order, monitor);
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static <K, V> int count(Node<K, V> node) {
        return node == null ? 0 : node.count;
    }

    private static <K, V> boolean isRed(Node<K, V> node) {
        return node != null && node.color == RED;
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

    private static <K, V> Entry<K, V> entryAtRoot(Node<K, V> node, int index) {
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

    private static <K, V> Node<K, V> withColor(Node<K, V> node, boolean color) {
        if (node.color == color) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, node.left, node.right, color);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> right = node.right;
        Node<K, V> newLeft = new Node<K, V>(
                node.key, node.value, node.left, right.left, RED);
        return new Node<K, V>(
                right.key, right.value, newLeft, right.right, node.color);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> left = node.left;
        Node<K, V> newRight = new Node<K, V>(
                node.key, node.value, left.right, node.right, RED);
        return new Node<K, V>(
                left.key, left.value, left.left, newRight, node.color);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value,
                withColor(node.left, !node.left.color),
                withColor(node.right, !node.right.color), !node.color);
    }

    private static <K, V> Node<K, V> insert(
            Node<K, V> node, K key, V value, Order<K> order) {
        if (node == null) {
            return new Node<K, V>(key, value, null, null, RED);
        }
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value,
                    insert(node.left, key, value, order), node.right, node.color);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.left,
                    insert(node.right, key, value, order), node.color);
        } else {
            node = new Node<K, V>(
                    node.key, value, node.left, node.right, node.color);
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
            node = new Node<K, V>(node.key, node.value, node.left,
                    rotateRight(node.right), node.color);
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
        node = new Node<K, V>(node.key, node.value,
                deleteMin(node.left), node.right, node.color);
        return balance(node);
    }

    private static <K, V> Node<K, V> delete(
            Node<K, V> node, K key, Order<K> order) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(node.key, node.value,
                    delete(node.left, key, order), node.right, node.color);
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
                node = new Node<K, V>(successor.key, successor.value, node.left,
                        deleteMin(node.right), node.color);
            } else {
                node = new Node<K, V>(node.key, node.value, node.left,
                        delete(node.right, key, order), node.color);
            }
        }
        return balance(node);
    }
}
