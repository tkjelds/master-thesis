@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node<T> {
        final T value;
        final Node<T> left;
        final Node<T> right;
        final boolean red;
        final int size;

        Node(T value, Node<T> left, Node<T> right, boolean red) {
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + nodeSize(left) + nodeSize(right);
        }
    }

    public static final class Snapshot<T> {
        private final Node<T> root;
        private final int size;
        private final Order<T> order;

        private Snapshot(Node<T> root, int size, Order<T> order) {
            this.root = root;
            this.size = size;
            this.order = order;
        }

        public int size() {
            return size;
        }

        public T get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }

            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = nodeSize(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return node.value;
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }

        public boolean contains(T item) {
            requireNonNull(item);
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.value);
                if (comparison == 0) {
                    return true;
                }
                node = comparison < 0 ? node.left : node.right;
            }
            return false;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int size;

    public TreeSet(Order<T> order) {
        requireNonNull(order);
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (find(root, item) != null) {
                return false;
            }
            root = insert(root, item);
            if (root.red) {
                root = recolor(root, false);
            }
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (find(root, item) == null) {
                return false;
            }

            Node<T> newRoot = root;
            if (!isRed(newRoot.left) && !isRed(newRoot.right)) {
                newRoot = recolor(newRoot, true);
            }
            newRoot = delete(newRoot, item);
            if (newRoot != null && newRoot.red) {
                newRoot = recolor(newRoot, false);
            }
            root = newRoot;
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return find(root, item) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }

            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = nodeSize(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return node.value;
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (lock) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.value);
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
        synchronized (lock) {
            return new Snapshot<T>(root, size, order);
        }
    }

    private Node<T> find(Node<T> node, T item) {
        while (node != null) {
            int comparison = order.compare(item, node.value);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, null, null, true);
        }

        int comparison = order.compare(item, node.value);
        if (comparison < 0) {
            node = withLeft(node, insert(node.left, item));
        } else {
            node = withRight(node, insert(node.right, item));
        }
        return balance(node);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.value) < 0) {
            if (node.left != null && !isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = withLeft(node, delete(node.left, item));
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            int comparison = order.compare(item, node.value);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (node.right != null && !isRed(node.right) && !isRed(node.right.left)) {
                node = moveRedRight(node);
            }

            comparison = order.compare(item, node.value);
            if (comparison == 0) {
                Node<T> successor = minimum(node.right);
                node = new Node<T>(successor.value, node.left, deleteMinimum(node.right), node.red);
            } else {
                node = withRight(node, delete(node.right, item));
            }
        }
        return balance(node);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = withLeft(node, deleteMinimum(node.left));
        return balance(node);
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            node = withRight(node, rotateRight(node.right));
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (node.left != null && isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> balance(Node<T> node) {
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

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.value, node.left, right.left, true);
        return new Node<T>(right.value, newLeft, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.value, left.right, node.right, true);
        return new Node<T>(left.value, left.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<T> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return new Node<T>(node.value, left, right, !node.red);
    }

    private Node<T> withLeft(Node<T> node, Node<T> left) {
        return new Node<T>(node.value, left, node.right, node.red);
    }

    private Node<T> withRight(Node<T> node, Node<T> right) {
        return new Node<T>(node.value, node.left, right, node.red);
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        return new Node<T>(node.value, node.left, node.right, red);
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }
}
