/**
 * A persistent left-leaning red-black tree guarded by one live-map monitor.
 * Absent lookups and removals return null without waiting for a mapping.
 */
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
        private final Node<K, V> root;
        private final Order<K> order;

        private Snapshot(Node<K, V> root, Order<K> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return count(root);
        }

        public V get(K key) {
            requireKey(key);
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            return indexedEntry(root, index);
        }
    }

    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int count;

        Node(K key, V value, boolean red,
             Node<K, V> left, Node<K, V> right) {
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
        }
    }

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
            Node<K, V> old = find(root, key, order);
            if (old == null && count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum mapping count reached");
            }
            Node<K, V> updated = insert(root, key, value);
            root = color(updated, false);
            return old == null ? null : old.value;
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
            Node<K, V> old = find(root, key, order);
            if (old == null) {
                return null;
            }
            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = color(updated, true);
            }
            updated = delete(updated, key);
            root = color(updated, false);
            return old.value;
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
            return count(root);
        }
    }

    public Entry<K, V> entryAt(int index) {
        synchronized (monitor) {
            return indexedEntry(root, index);
        }
    }

    public int rank(K key) {
        requireKey(key);
        synchronized (monitor) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += count(node.left) + 1;
                    node = node.right;
                } else {
                    return result + count(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<K, V> snapshot() {
        synchronized (monitor) {
            return new Snapshot<K, V>(root, order);
        }
    }

    private static void requireKey(Object key) {
        if (key == null) {
            throw new NullPointerException("key");
        }
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.count;
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

    private static <K, V> Entry<K, V> indexedEntry(
            Node<K, V> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
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
        throw new AssertionError("Inconsistent subtree counts");
    }

    private static <K, V> Node<K, V> color(Node<K, V> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<K, V>(
                node.key, node.value, red, node.left, node.right);
    }

    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<K, V>(key, value, true, null, null);
        }
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = new Node<K, V>(node.key, node.value, node.red,
                    insert(node.left, key, value), node.right);
        } else if (comparison > 0) {
            node = new Node<K, V>(node.key, node.value, node.red,
                    node.left, insert(node.right, key, value));
        } else {
            return new Node<K, V>(
                    node.key, value, node.red, node.left, node.right);
        }
        return balance(node);
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<K, V>(node.key, node.value, node.red,
                    delete(node.left, key), node.right);
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
                node = new Node<K, V>(
                        successor.key, successor.value, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = new Node<K, V>(node.key, node.value, node.red,
                        node.left, delete(node.right, key));
            }
        }
        return balance(node);
    }

    private Node<K, V> deleteMinimum(Node<K, V> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<K, V>(node.key, node.value, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right;
        Node<K, V> left = new Node<K, V>(
                node.key, node.value, true, node.left, pivot.left);
        return new Node<K, V>(
                pivot.key, pivot.value, node.red, left, pivot.right);
    }

    private Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left;
        Node<K, V> right = new Node<K, V>(
                node.key, node.value, true, pivot.right, node.right);
        return new Node<K, V>(
                pivot.key, pivot.value, node.red, pivot.left, right);
    }

    private Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(node.key, node.value, !node.red,
                color(node.left, !node.left.red),
                color(node.right, !node.right.red));
    }

    private Node<K, V> moveRedLeft(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = new Node<K, V>(node.key, node.value, node.red,
                    node.left, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<K, V> moveRedRight(Node<K, V> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<K, V> balance(Node<K, V> node) {
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
