/** A deterministic total order; comparisons must not mutate or reenter the bag. */
interface Order<T> {
    int compare(T left, T right);
}

/**
 * An indexed multiset backed by an immutable, path-copied left-leaning
 * red-black tree. Operations wait only for monitor contention. Absent removal
 * returns false, invalid selection throws, and size overflow throws.
 *
 * Elements, including those retained by snapshots, must keep their ordering.
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
            if (total(root) == 2147483647) {
                throw new IllegalStateException("TreeBag size overflow");
            }
            root = color(insert(root, item), false);
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            if (found == null) {
                return false;
            }
            if (found.occurrences > 1) {
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
            Node<T> found = find(root, item, order);
            return found == null ? 0 : found.occurrences;
        }
    }

    public int size() {
        synchronized (monitor) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinct(root);
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
                } else {
                    result += total(node.left);
                    if (comparison == 0) {
                        return result;
                    }
                    result += node.occurrences;
                    node = node.right;
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order, monitor);
        }
    }

    public static final class Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;
        private final Object monitor;

        private Snapshot(Node<T> root, Order<T> order, Object monitor) {
            this.root = root;
            this.order = order;
            this.monitor = monitor;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct(root);
        }

        public int count(T item) {
            requireItem(item);
            // Serialize use of the supplied order with live comparisons.
            synchronized (monitor) {
                Node<T> found = find(root, item, order);
                return found == null ? 0 : found.occurrences;
            }
        }

        public T get(int index) {
            return select(root, index);
        }
    }

    private static final class Node<E> {
        final E item;
        final int occurrences;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int total;
        final int distinct;

        Node(E item, int occurrences, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.occurrences = occurrences;
            this.left = left;
            this.right = right;
            this.red = red;
            this.total = total(left) + occurrences + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int total(Node<?> node) {
        return node == null ? 0 : node.total;
    }

    private static int distinct(Node<?> node) {
        return node == null ? 0 : node.distinct;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <E> E select(Node<E> node, int index) {
        if (index < 0 || index >= total(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int leftTotal = total(node.left);
            if (index < leftTotal) {
                node = node.left;
            } else if (index - leftTotal < node.occurrences) {
                return node.item;
            } else {
                index -= leftTotal + node.occurrences;
                node = node.right;
            }
        }
        throw new AssertionError("Inconsistent subtree totals");
    }

    private static <E> Node<E> color(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.occurrences, node.left, node.right, red);
    }

    private static <E> Node<E> withLeft(Node<E> node, Node<E> left) {
        return new Node<E>(node.item, node.occurrences, left, node.right, node.red);
    }

    private static <E> Node<E> withRight(Node<E> node, Node<E> right) {
        return new Node<E>(node.item, node.occurrences, node.left, right, node.red);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> pivot = node.right;
        Node<E> left = new Node<E>(
                node.item, node.occurrences, node.left, pivot.left, true);
        return new Node<E>(
                pivot.item, pivot.occurrences, left, pivot.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> pivot = node.left;
        Node<E> right = new Node<E>(
                node.item, node.occurrences, pivot.right, node.right, true);
        return new Node<E>(
                pivot.item, pivot.occurrences, pivot.left, right, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, node.occurrences,
                color(node.left, !node.left.red),
                color(node.right, !node.right.red), !node.red);
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, null, null, true);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = withLeft(node, insert(node.left, item));
        } else if (comparison > 0) {
            node = withRight(node, insert(node.right, item));
        } else {
            return new Node<T>(node.item, node.occurrences + 1,
                    node.left, node.right, node.red);
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

    private Node<T> decrement(Node<T> node, T item) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return withLeft(node, decrement(node.left, item));
        }
        if (comparison > 0) {
            return withRight(node, decrement(node.right, item));
        }
        return new Node<T>(node.item, node.occurrences - 1,
                node.left, node.right, node.red);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = withRight(node, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> balance(Node<E> node) {
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

    private static <E> Node<E> deleteMinimum(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        return balance(withLeft(node, deleteMinimum(node.left)));
    }

    // Called only for a present class whose last occurrence is being removed.
    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = withLeft(node, delete(node.left, item));
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(item, node.item) == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !isRed(node.right.left)) {
                node = moveRedRight(node);
            }
            if (order.compare(item, node.item) == 0) {
                Node<T> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                // Move the entire successor class, not just one occurrence.
                node = new Node<T>(successor.item, successor.occurrences,
                        node.left, deleteMinimum(node.right), node.red);
            } else {
                node = withRight(node, delete(node.right, item));
            }
        }
        return balance(node);
    }
}
