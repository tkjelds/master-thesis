interface Order<K> {
    int compare(K left, K right);
}

@TreeDictionary.ThreadSafe
public final class TreeDictionary<K, V> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Object lock = new Object();
    private final Order<K> order;
    private Node<K, V> root;

    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int count;

        Node(K key, V value, boolean red, Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
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
        private final Object lock;

        private Snapshot(Node<K, V> root, Order<K> order, Object lock) {
            this.root = root;
            this.order = order;
            this.lock = lock;
        }

        public int size() {
            return count(root);
        }

        public V get(K key) {
            if (key == null) {
                throw new NullPointerException();
            }
            synchronized (lock) {
                Node<K, V> node = find(root, key, order);
                return node == null ? null : node.value;
            }
        }

        public Entry<K, V> entryAt(int index) {
            return entryAtNode(root, index);
        }
    }

    private static final class PutResult<V> {
        V previous;
    }

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
        synchronized (lock) {
            PutResult<V> result = new PutResult<V>();
            Node<K, V> updated = insert(root, key, value, result,
                    count(root) == Integer.MAX_VALUE);
            root = withColor(updated, BLACK);
            return result.previous;
        }
    }

    public V get(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }
    }

    public V remove(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node<K, V> existing = find(root, key, order);
            if (existing == null) {
                return null;
            }

            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, RED);
            }
            updated = delete(updated, key);
            if (updated != null) {
                updated = withColor(updated, BLACK);
            }
            root = updated;
            return existing.value;
        }
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            return find(root, key, order) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (lock) {
            return entryAtNode(root, index);
        }
    }

    public int rank(K key) {
        if (key == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += 1 + count(node.left);
                    node = node.right;
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (lock) {
            return new Snapshot<K, V>(root, order, lock);
        }
    }

    private static <K, V> int count(Node<K, V> node) {
        return node == null ? 0 : node.count;
    }

    private static <K, V> boolean isRed(Node<K, V> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> withColor(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(node.key, node.value, red, node.left, node.right);
    }

    private static <K, V> Node<K, V> find(
            Node<K, V> node, K key, Order<K> order) {
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <K, V> Entry<K, V> entryAtNode(Node<K, V> root, int index) {
        if (index < 0 || index >= count(root)) {
            throw new IndexOutOfBoundsException();
        }
        Node<K, V> node = root;
        int position = index;
        while (true) {
            int leftCount = count(node.left);
            if (position < leftCount) {
                node = node.left;
            } else if (position == leftCount) {
                return new Entry<K, V>(node.key, node.value);
            } else {
                position -= leftCount + 1;
                node = node.right;
            }
        }
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
        Node<K, V> left = node.left == null
                ? null : withColor(node.left, !node.left.red);
        Node<K, V> right = node.right == null
                ? null : withColor(node.right, !node.right.red);
        return new Node<K, V>(node.key, node.value, !node.red, left, right);
    }

    private Node<K, V> insert(
            Node<K, V> node, K key, V value, PutResult<V> result, boolean full) {
        if (node == null) {
            if (full) {
                throw new IllegalStateException("Maximum size reached");
            }
            return new Node<K, V>(key, value, RED, null, null);
        }

        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            Node<K, V> left = insert(node.left, key, value, result, full);
            node = new Node<K, V>(node.key, node.value, node.red, left, node.right);
        } else if (comparison > 0) {
            Node<K, V> right = insert(node.right, key, value, result, full);
            node = new Node<K, V>(node.key, node.value, node.red, node.left, right);
        } else {
            result.previous = node.value;
            return new Node<K, V>(node.key, value, node.red, node.left, node.right);
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

    private static <K, V> Node<K, V> minimum(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <K, V> Node<K, V> deleteMinimum(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        Node<K, V> left = deleteMinimum(node.left);
        node = new Node<K, V>(node.key, node.value, node.red, left, node.right);
        return balance(node);
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        if (order.compare(key, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                Node<K, V> left = delete(node.left, key);
                node = new Node<K, V>(
                        node.key, node.value, node.red, left, node.right);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(key, node.key) == 0 && node.right == null) {
                return null;
            }
            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }
                if (order.compare(key, node.key) == 0) {
                    Node<K, V> successor = minimum(node.right);
                    Node<K, V> right = deleteMinimum(node.right);
                    node = new Node<K, V>(
                            successor.key, successor.value, node.red, node.left, right);
                } else {
                    Node<K, V> right = delete(node.right, key);
                    node = new Node<K, V>(
                            node.key, node.value, node.red, node.left, right);
                }
            }
        }
        return balance(node);
    }
}
