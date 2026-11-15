interface Order<T> {
    int compare(T left, T right);
}

/**
 * An immutable snapshot retaining element references, not deep copies.
 */
@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet.Node<T> root;
    private final Order<T> order;

    Snapshot(TreeSet.Node<T> root, Order<T> order) {
        this.root = root;
        this.order = order;
    }

    public int size() {
        return TreeSet.nodeSize(root);
    }

    public T get(int index) {
        return TreeSet.elementAt(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        return TreeSet.hasElement(root, item, order);
    }
}

/**
 * A concurrent indexed set backed by an immutable left-leaning red-black tree.
 *
 * Live operations synchronize on a private monitor. Snapshots share immutable
 * nodes and require no locking. Elements must retain their ordering.
 *
 * Operations do not wait for elements or capacity: removing from an empty set
 * returns false, and invalid indices throw IndexOutOfBoundsException.
 * Adding a distinct element at the maximum representable int size throws
 * IllegalStateException without changing the set.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    private static final int MAX_SIZE = 0x7fffffff;

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    static final class Node<T> {
        private final T item;
        private final boolean red;
        private final Node<T> left;
        private final Node<T> right;
        private final int size;

        private Node(T item, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + nodeSize(left) + nodeSize(right);
        }
    }

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (hasElement(root, item, order)) {
                return false;
            }
            if (nodeSize(root) == MAX_SIZE) {
                throw new IllegalStateException("Maximum set size reached");
            }
            Node<T> updated = insert(root, item);
            root = withColor(updated, false);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (!hasElement(root, item, order)) {
                return false;
            }
            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, true);
            }
            updated = delete(updated, item);
            root = updated == null ? null : withColor(updated, false);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            return hasElement(root, item, order);
        }
    }

    public int size() {
        synchronized (monitor) {
            return nodeSize(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return elementAt(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += 1 + nodeSize(node.left);
                    node = node.right;
                } else {
                    return result + nodeSize(node.left);
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

    static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    static <T> boolean hasElement(Node<T> node, T item, Order<T> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    static <T> T elementAt(Node<T> node, int index) {
        if (index < 0 || index >= nodeSize(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int leftSize = nodeSize(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index > leftSize) {
                index -= leftSize + 1;
                node = node.right;
            } else {
                return node.item;
            }
        }
        throw new AssertionError("Inconsistent subtree sizes");
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <T> Node<T> withColor(Node<T> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<T>(node.item, red, node.left, node.right);
    }

    private static <T> Node<T> withLeft(Node<T> node, Node<T> left) {
        return new Node<T>(node.item, node.red, left, node.right);
    }

    private static <T> Node<T> withRight(Node<T> node, Node<T> right) {
        return new Node<T>(node.item, node.red, node.left, right);
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
        Node<T> left = new Node<T>(node.item, true, node.left, pivot.left);
        return new Node<T>(pivot.item, node.red, left, pivot.right);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> pivot = node.left;
        Node<T> right = new Node<T>(node.item, true, pivot.right, node.right);
        return new Node<T>(pivot.item, node.red, pivot.left, right);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, !node.red,
                withColor(node.left, !node.left.red),
                withColor(node.right, !node.right.red));
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, true, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = withLeft(node, insert(node.left, item));
        } else if (comparison > 0) {
            node = withRight(node, insert(node.right, item));
        } else {
            return node;
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

    private Node<T> delete(Node<T> node, T item) {
        // The preliminary search guarantees that the selected child exists.
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
                node = new Node<T>(successor.item, node.red,
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
}
