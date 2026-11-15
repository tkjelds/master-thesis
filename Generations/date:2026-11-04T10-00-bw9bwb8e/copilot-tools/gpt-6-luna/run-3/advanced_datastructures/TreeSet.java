interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
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
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item) != null) {
                return false;
            }
            root = put(root, item);
            if (isRed(root)) {
                root = recolor(root, false);
            }
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item) == null) {
                return false;
            }
            Node<T> start = root;
            if (!isRed(start.left) && !isRed(start.right)) {
                start = recolor(start, true);
            }
            root = delete(start, item);
            if (root != null && isRed(root)) {
                root = recolor(root, false);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return find(root, item) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return nodeSize(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkIndex(index, nodeSize(root));
            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = nodeSize(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return node.item;
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
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

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order);
        }
    }

    private static void requireNonNull(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private Node<T> find(Node<T> node, T item) {
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

    private static <T> Node<T> put(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, null, null, true);
        }

        int comparison = 0;
        // The caller has already checked that the item is not present.
        // This placeholder is replaced by the order-aware helper below.
        return node;
    }

    private Node<T> put(Node<T> node, T item, boolean unused) {
        if (node == null) {
            return new Node<T>(item, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<T> result;
        if (comparison < 0) {
            result = new Node<T>(node.item, put(node.left, item, true), node.right, node.red);
        } else if (comparison > 0) {
            result = new Node<T>(node.item, node.left, put(node.right, item, true), node.red);
        } else {
            return node;
        }

        if (isRed(result.right) && !isRed(result.left)) {
            result = rotateLeft(result);
        }
        if (isRed(result.left) && isRed(result.left.left)) {
            result = rotateRight(result);
        }
        if (isRed(result.left) && isRed(result.right)) {
            result = flipColors(result);
        }
        return result;
    }

    private Node<T> delete(Node<T> node, T item) {
        Node<T> result = node;
        if (order.compare(item, result.item) < 0) {
            if (!isRed(result.left) && !isRed(result.left.left)) {
                result = moveRedLeft(result);
            }
            result = new Node<T>(result.item, delete(result.left, item), result.right, result.red);
        } else {
            if (isRed(result.left)) {
                result = rotateRight(result);
            }
            int comparison = order.compare(item, result.item);
            if (comparison == 0 && result.right == null) {
                return null;
            }
            if (!isRed(result.right) && !isRed(result.right.left)) {
                result = moveRedRight(result);
            }
            comparison = order.compare(item, result.item);
            if (comparison == 0) {
                Node<T> successor = minimum(result.right);
                result = new Node<T>(successor.item, result.left, deleteMinimum(result.right), result.red);
            } else {
                result = new Node<T>(result.item, result.left, delete(result.right, item), result.red);
            }
        }
        return balance(result);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        Node<T> result = node;
        if (!isRed(result.left) && !isRed(result.left.left)) {
            result = moveRedLeft(result);
        }
        result = new Node<T>(result.item, deleteMinimum(result.left), result.right, result.red);
        return balance(result);
    }

    private static <T> Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> left = new Node<T>(node.item, node.left, right.left, true);
        return new Node<T>(right.item, left, right.right, node.red);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> right = new Node<T>(node.item, left.right, node.right, true);
        return new Node<T>(left.item, left.left, right, node.red);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        Node<T> left = recolor(node.left, !node.left.red);
        Node<T> right = recolor(node.right, !node.right.red);
        return new Node<T>(node.item, left, right, !node.red);
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        return new Node<T>(node.item, node.left, node.right, red);
    }

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> result = flipColors(node);
        if (isRed(result.right.left)) {
            Node<T> right = rotateRight(result.right);
            result = new Node<T>(result.item, result.left, right, result.red);
            result = rotateLeft(result);
            result = flipColors(result);
        }
        return result;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> result = flipColors(node);
        if (isRed(result.left.left)) {
            result = rotateRight(result);
            result = flipColors(result);
        }
        return result;
    }

    private static <T> Node<T> balance(Node<T> node) {
        Node<T> result = node;
        if (isRed(result.right)) {
            result = rotateLeft(result);
        }
        if (isRed(result.left) && isRed(result.left.left)) {
            result = rotateRight(result);
        }
        if (isRed(result.left) && isRed(result.right)) {
            result = flipColors(result);
        }
        return result;
    }
}

@Node.ThreadSafe
final class Node<T> {
    @interface ThreadSafe {}

    final T item;
    final Node<T> left;
    final Node<T> right;
    final boolean red;
    final int size;

    Node(T item, Node<T> left, Node<T> right, boolean red) {
        this.item = item;
        this.left = left;
        this.right = right;
        this.red = red;
        this.size = 1 + (left == null ? 0 : left.size) + (right == null ? 0 : right.size);
    }
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final Node<T> root;
    private final Order<T> order;

    Snapshot(Node<T> root, Order<T> order) {
        this.root = root;
        this.order = order;
    }

    public int size() {
        return root == null ? 0 : root.size;
    }

    public T get(int index) {
        int size = size();
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
        Node<T> node = root;
        int remaining = index;
        while (node != null) {
            int leftSize = node.left == null ? 0 : node.left.size;
            if (remaining < leftSize) {
                node = node.left;
            } else if (remaining == leftSize) {
                return node.item;
            } else {
                remaining -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        Node<T> node = root;
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return true;
            }
        }
        return false;
    }
}
