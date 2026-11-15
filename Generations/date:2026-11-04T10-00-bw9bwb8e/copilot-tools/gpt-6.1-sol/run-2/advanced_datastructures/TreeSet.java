interface Order<T> {
    int compare(T left, T right);
}

/**
 * An indexed set backed by an immutable, path-copied red-black tree.
 * Operations wait only to acquire the collection's monitor. An empty set
 * does not otherwise block. Adding a new element at Integer.MAX_VALUE
 * elements throws IllegalStateException.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (containsNode(root, item, order)) {
                return false;
            }
            if (count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum set size reached");
            }
            root = color(insert(root, item), false);
            return true;
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (!containsNode(root, item, order)) {
                return false;
            }
            Node<T> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = color(next, true);
            }
            root = color(delete(next, item), false);
            return true;
        }
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (monitor) {
            return containsNode(root, item, order);
        }
    }

    public int size() {
        synchronized (monitor) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return elementAt(root, index);
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
                    result += count(node.left) + 1;
                    node = node.right;
                } else {
                    return result + count(node.left);
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
            return count(root);
        }

        public T get(int index) {
            return elementAt(root, index);
        }

        public boolean contains(T item) {
            requireItem(item);
            return containsNode(root, item, order);
        }
    }

    private static final class Node<T> {
        final T item;
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final int count;

        Node(T item, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <T> Node<T> color(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, red, node.left, node.right);
    }

    private static <T> boolean containsNode(
            Node<T> node, T item, Order<T> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    private static <T> T elementAt(Node<T> node, int index) {
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
                return node.item;
            }
        }
        throw new AssertionError("Invalid subtree counts");
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, true, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(
                    node.item, node.red, insert(node.left, item), node.right);
        } else if (comparison > 0) {
            node = new Node<T>(
                    node.item, node.red, node.left, insert(node.right, item));
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

    // The initial membership check guarantees the chosen subtree exists.
    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(
                    node.item, node.red, delete(node.left, item), node.right);
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
                node = new Node<T>(
                        successor.item, node.red, node.left, deleteMin(node.right));
            } else {
                node = new Node<T>(
                        node.item, node.red, node.left, delete(node.right, item));
            }
        }
        return balance(node);
    }

    private static <T> Node<T> deleteMin(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        return balance(new Node<T>(
                node.item, node.red, deleteMin(node.left), node.right));
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
                color(node.left, !node.left.red),
                color(node.right, !node.right.red));
    }

    private static <T> Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = new Node<T>(
                    node.item, node.red, node.left, rotateRight(node.right));
            node = flipColors(rotateLeft(node));
        }
        return node;
    }

    private static <T> Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
            node = flipColors(rotateRight(node));
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
