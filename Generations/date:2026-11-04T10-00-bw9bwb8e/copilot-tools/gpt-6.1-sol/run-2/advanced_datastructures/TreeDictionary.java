/**
 * A persistent red-black map with monitor-serialized live operations.
 * Operations do not wait for data or capacity. Absent lookups and removals
 * return null; invalid indices throw IndexOutOfBoundsException. Exceeding
 * the int-sized mapping limit throws IllegalStateException without mutation.
 * Keys must retain their ordering. Keys and values are not deep-copied.
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
            requireNonNull(key);
            Node<K, V> node = find(root, key, order);
            return node == null ? null : node.value;
        }

        public Entry<K, V> entryAt(int index) {
            return select(root, index);
        }
    }

    private static final class Node<K, V> {
        final K key;
        final V value;
        final boolean red;
        final Node<K, V> left;
        final Node<K, V> right;
        final int count;

        Node(K key, V value, boolean red, Node<K, V> left,
             Node<K, V> right) {
            long total = 1L + count(left) + count(right);
            if (total > Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum mapping count exceeded");
            }
            this.key = key;
            this.value = value;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = (int) total;
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
            Node<K, V> previous = find(root, key, order);
            Node<K, V> updated = insert(root, key, value);
            root = color(updated, false);
            return previous == null ? null : previous.value;
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
            Node<K, V> previous = find(root, key, order);
            if (previous == null) {
                return null;
            }
            Node<K, V> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = color(updated, true);
            }
            updated = delete(updated, key);
            root = color(updated, false);
            return previous.value;
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
            return select(root, index);
        }
    }

    public int rank(K key) {
        requireNonNull(key);
        synchronized (monitor) {
            int result = 0;
            Node<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(key, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += 1 + count(node.left);
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

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static int count(Node<?, ?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?, ?> node) {
        return node != null && node.red;
    }

    private static <K, V> Node<K, V> find(Node<K, V> node, K key,
                                         Order<K> order) {
        while (node != null) {
            int comparison = order.compare(key, node.key);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <K, V> Entry<K, V> select(Node<K, V> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException("Index: " + index);
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
        return new Node<K, V>(node.key, node.value, red, node.left, node.right);
    }

    private static <K, V> Node<K, V> left(Node<K, V> node,
                                         Node<K, V> child) {
        return new Node<K, V>(node.key, node.value, node.red, child, node.right);
    }

    private static <K, V> Node<K, V> right(Node<K, V> node,
                                          Node<K, V> child) {
        return new Node<K, V>(node.key, node.value, node.red, node.left, child);
    }

    private static <K, V> Node<K, V> rotateLeft(Node<K, V> node) {
        Node<K, V> pivot = node.right;
        Node<K, V> child = new Node<K, V>(
                node.key, node.value, true, node.left, pivot.left);
        return new Node<K, V>(
                pivot.key, pivot.value, node.red, child, pivot.right);
    }

    private static <K, V> Node<K, V> rotateRight(Node<K, V> node) {
        Node<K, V> pivot = node.left;
        Node<K, V> child = new Node<K, V>(
                node.key, node.value, true, pivot.right, node.right);
        return new Node<K, V>(
                pivot.key, pivot.value, node.red, pivot.left, child);
    }

    private static <K, V> Node<K, V> flipColors(Node<K, V> node) {
        return new Node<K, V>(
                node.key, node.value, !node.red,
                color(node.left, !node.left.red),
                color(node.right, !node.right.red));
    }

    private Node<K, V> insert(Node<K, V> node, K key, V value) {
        if (node == null) {
            return new Node<K, V>(key, value, true, null, null);
        }
        int comparison = order.compare(key, node.key);
        if (comparison < 0) {
            node = left(node, insert(node.left, key, value));
        } else if (comparison > 0) {
            node = right(node, insert(node.right, key, value));
        } else {
            node = new Node<K, V>(
                    node.key, value, node.red, node.left, node.right);
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
            node = right(node, rotateRight(node.right));
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
        return balance(left(node, deleteMinimum(node.left)));
    }

    private Node<K, V> delete(Node<K, V> node, K key) {
        if (order.compare(key, node.key) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = left(node, delete(node.left, key));
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
                Node<K, V> successor = minimum(node.right);
                node = new Node<K, V>(
                        successor.key, successor.value, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = right(node, delete(node.right, key));
            }
        }
        return balance(node);
    }
}
