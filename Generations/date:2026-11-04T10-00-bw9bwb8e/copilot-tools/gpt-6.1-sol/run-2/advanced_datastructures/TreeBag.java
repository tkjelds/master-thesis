interface Order<T> {
    int compare(T left, T right);
}

/**
 * An indexed multiset whose elements must retain their ordering.
 * Operations do not wait for elements or capacity: absent removals return false,
 * invalid indices throw, and additions at Integer.MAX_VALUE occurrences throw
 * IllegalStateException. Snapshots retain element references, not deep copies.
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
                throw new IllegalStateException("Maximum size reached");
            }
            root = recolor(insert(root, item), false);
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            if (found == null) {
                return false;
            }
            if (found.count > 1) {
                root = decrement(root, item);
            } else {
                Node<T> next = root;
                if (!isRed(next.left) && !isRed(next.right)) {
                    next = recolor(next, true);
                }
                next = delete(next, item);
                root = next == null ? null : recolor(next, false);
            }
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            return found == null ? 0 : found.count;
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
            Node<T> found = find(root, item, order);
            return found == null ? 0 : found.count;
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

    private static <T> Node<T> find(Node<T> node, T item, Order<T> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
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

    private static <T> T select(Node<T> node, int index) {
        if (index < 0 || index >= sizeOf(node)) {
            throw new IndexOutOfBoundsException("Index out of bounds");
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
        throw new AssertionError("Inconsistent subtree size");
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, true, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = withLeft(node, insert(node.left, item));
        } else if (comparison > 0) {
            node = withRight(node, insert(node.right, item));
        } else {
            return new Node<T>(node.item, node.count + 1, node.red,
                    node.left, node.right);
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
        return new Node<T>(node.item, node.count - 1, node.red,
                node.left, node.right);
    }

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
                // Move the entire equivalence class, not just one occurrence.
                node = new Node<T>(successor.item, successor.count, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = withRight(node, delete(node.right, item));
            }
        }
        return balance(node);
    }

    private static <T> Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        return balance(withLeft(node, deleteMinimum(node.left)));
    }

    private static <T> Node<T> withLeft(Node<T> node, Node<T> left) {
        return new Node<T>(node.item, node.count, node.red, left, node.right);
    }

    private static <T> Node<T> withRight(Node<T> node, Node<T> right) {
        return new Node<T>(node.item, node.count, node.red, node.left, right);
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.count, red, node.left, node.right);
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> top = node.right;
        Node<T> left = new Node<T>(node.item, node.count, true,
                node.left, top.left);
        return new Node<T>(top.item, top.count, node.red, left, top.right);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> top = node.left;
        Node<T> right = new Node<T>(node.item, node.count, true,
                top.right, node.right);
        return new Node<T>(top.item, top.count, node.red, top.left, right);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, node.count, !node.red,
                recolor(node.left, !node.left.red),
                recolor(node.right, !node.right.red));
    }

    private static <T> Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = withRight(node, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <T> Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <T> Node<T> balance(Node<T> node) {
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
