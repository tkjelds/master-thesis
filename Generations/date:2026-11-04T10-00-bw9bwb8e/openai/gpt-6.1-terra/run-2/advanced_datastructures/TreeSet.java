interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E item, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> withColor(Node<E> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<E>(node.item, red, node.left, node.right);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(
                node.item, RED, node.left, right.left);
        return new Node<E>(right.item, node.red, left, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(
                node.item, RED, left.right, node.right);
        return new Node<E>(left.item, node.red, left.left, right);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = withColor(node.left, !node.left.red);
        Node<E> right = withColor(node.right, !node.right.red);
        return new Node<E>(node.item, !node.red, left, right);
    }

    private static <E> Node<E> balance(Node<E> node) {
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

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.item, node.red, node.left, right);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        node = flipColors(node);
        if (node.left != null && isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, RED, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(
                    node.item, node.red, insert(node.left, item), node.right);
        } else {
            node = new Node<T>(
                    node.item, node.red, node.left, insert(node.right, item));
        }
        return balance(node);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        while (node.left != null) {
            node = node.left;
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
        node = new Node<E>(
                node.item, node.red, deleteMinimum(node.left), node.right);
        return balance(node);
    }

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
                Node<T> successor = minimum(node.right);
                node = new Node<T>(
                        successor.item, node.red, node.left,
                        deleteMinimum(node.right));
            } else {
                node = new Node<T>(
                        node.item, node.red, node.left,
                        delete(node.right, item));
            }
        }
        return balance(node);
    }

    static <E> boolean containsNode(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    static <E> E getNode(Node<E> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException();
        }
        while (true) {
            int leftCount = count(node.left);
            if (index < leftCount) {
                node = node.left;
            } else if (index == leftCount) {
                return node.item;
            } else {
                index -= leftCount + 1;
                node = node.right;
            }
        }
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            if (containsNode(root, item, order)) {
                return false;
            }
            if (count(root) == 2147483647) {
                throw new IllegalStateException("Set size exceeds int range");
            }
            Node<T> updated = insert(root, item);
            root = withColor(updated, BLACK);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            if (!containsNode(root, item, order)) {
                return false;
            }
            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, RED);
            }
            updated = delete(updated, item);
            if (updated != null) {
                updated = withColor(updated, BLACK);
            }
            root = updated;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
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
            return getNode(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += count(node.left) + 1;
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
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet.Node<T> root;
    private final Order<T> order;
    private final Object monitor;

    Snapshot(TreeSet.Node<T> root, Order<T> order, Object monitor) {
        this.root = root;
        this.order = order;
        this.monitor = monitor;
    }

    public int size() {
        return TreeSet.count(root);
    }

    public T get(int index) {
        return TreeSet.getNode(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            return TreeSet.containsNode(root, item, order);
        }
    }
}
