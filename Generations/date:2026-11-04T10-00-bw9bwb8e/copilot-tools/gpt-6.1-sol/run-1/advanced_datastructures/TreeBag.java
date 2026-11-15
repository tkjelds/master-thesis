interface Order<T> {
    int compare(T left, T right);
}

/**
 * A concurrent indexed multiset using a persistent red-black tree.
 *
 * Operations do not wait for elements or capacity. An absent removal returns
 * false, an invalid index throws IndexOutOfBoundsException, and an addition
 * exceeding Integer.MAX_VALUE occurrences throws IllegalStateException.
 *
 * Elements must retain their ordering while held by this bag or a snapshot.
 */
@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (sizeOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size overflow");
            }
            root = color(insert(root, item), false);
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            int occurrences = findCount(root, item, order);
            if (occurrences == 0) {
                return false;
            }
            if (occurrences > 1) {
                root = decrement(root, item);
            } else {
                Node<T> next = root;
                if (!isRed(next.left) && !isRed(next.right)) {
                    next = color(next, true);
                }
                root = color(delete(next, item), false);
            }
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (monitor) {
            return findCount(root, item, order);
        }
    }

    public int size() {
        synchronized (monitor) {
            return sizeOf(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinctOf(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += sizeOf(node.left) + node.count;
                    node = node.right;
                } else {
                    return result + sizeOf(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order);
        }
    }

    /**
     * Immutable historical structure; element references are not deep copied.
     */
    public static final class Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        private Snapshot(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return sizeOf(root);
        }

        public int distinctSize() {
            return distinctOf(root);
        }

        public int count(T item) {
            requireItem(item);
            return findCount(root, item, order);
        }

        public T get(int index) {
            return select(root, index);
        }
    }

    private static final class Node<T> {
        final T item;
        final int count;
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final int size;
        final int distinct;

        Node(T item, int count, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = sizeOf(left) + count + sizeOf(right);
            this.distinct = distinctOf(left) + 1 + distinctOf(right);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static int distinctOf(Node<?> node) {
        return node == null ? 0 : node.distinct;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static boolean hasRedLeft(Node<?> node) {
        return node != null && isRed(node.left);
    }

    private static <T> int findCount(Node<T> node, T item, Order<T> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return node.count;
            }
        }
        return 0;
    }

    private static <T> T select(Node<T> node, int index) {
        if (index < 0 || index >= sizeOf(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index - leftSize < node.count) {
                return node.item;
            } else {
                index -= leftSize + node.count;
                node = node.right;
            }
        }
        throw new AssertionError("Inconsistent subtree sizes");
    }

    private static <T> Node<T> color(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.count, red, node.left, node.right);
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
        Node<T> left = new Node<T>(
                node.item, node.count, true, node.left, pivot.left);
        return new Node<T>(
                pivot.item, pivot.count, node.red, left, pivot.right);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> pivot = node.left;
        Node<T> right = new Node<T>(
                node.item, node.count, true, pivot.right, node.right);
        return new Node<T>(
                pivot.item, pivot.count, node.red, pivot.left, right);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        return new Node<T>(
                node.item, node.count, !node.red,
                color(node.left, !isRed(node.left)),
                color(node.right, !isRed(node.right)));
    }

    private static <T> Node<T> balance(Node<T> node) {
        if (isRed(node.right)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && hasRedLeft(node.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, true, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(node.item, node.count, node.red,
                    insert(node.left, item), node.right);
        } else if (comparison > 0) {
            node = new Node<T>(node.item, node.count, node.red,
                    node.left, insert(node.right, item));
        } else {
            return new Node<T>(node.item, node.count + 1, node.red,
                    node.left, node.right);
        }
        if (isRed(node.right) && !isRed(node.left)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && hasRedLeft(node.left)) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> decrement(Node<T> node, T item) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<T>(node.item, node.count, node.red,
                    decrement(node.left, item), node.right);
        }
        if (comparison > 0) {
            return new Node<T>(node.item, node.count, node.red,
                    node.left, decrement(node.right, item));
        }
        return new Node<T>(node.item, node.count - 1, node.red,
                node.left, node.right);
    }

    private static <T> Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (hasRedLeft(node.right)) {
            node = new Node<T>(node.item, node.count, node.red,
                    node.left, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <T> Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (hasRedLeft(node.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !hasRedLeft(node.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.item, node.count, node.red,
                    delete(node.left, item), node.right);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(item, node.item) == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !hasRedLeft(node.right)) {
                node = moveRedRight(node);
            }
            if (order.compare(item, node.item) == 0) {
                Node<T> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                // Transfer the entire equivalence class, not one occurrence.
                node = new Node<T>(successor.item, successor.count, node.red,
                        node.left, deleteMinimumClass(node.right));
            } else {
                node = new Node<T>(node.item, node.count, node.red,
                        node.left, delete(node.right, item));
            }
        }
        return balance(node);
    }

    private static <T> Node<T> deleteMinimumClass(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !hasRedLeft(node.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<T>(node.item, node.count, node.red,
                deleteMinimumClass(node.left), node.right);
        return balance(node);
    }
}
