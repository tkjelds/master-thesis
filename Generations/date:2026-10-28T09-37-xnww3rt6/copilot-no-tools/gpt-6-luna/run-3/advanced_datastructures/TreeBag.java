@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E key;
        final int count;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int subtreeSize;
        final int subtreeDistinctSize;

        Node(E key, int count, boolean red, Node<E> left, Node<E> right) {
            this.key = key;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.subtreeSize = sizeOf(left) + count + sizeOf(right);
            this.subtreeDistinctSize = distinctSizeOf(left) + 1 + distinctSizeOf(right);
        }
    }

    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;

        private Snapshot(Order<E> order, Node<E> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return sizeOf(root);
        }

        public int distinctSize() {
            return distinctSizeOf(root);
        }

        public int count(E item) {
            requireNonNull(item);
            Node<E> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public E get(int index) {
            int totalSize = sizeOf(root);
            if (index < 0 || index >= totalSize) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<E> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining < leftSize + node.count) {
                    return node.key;
                } else {
                    remaining -= leftSize + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

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
        requireNonNull(item);
        synchronized (monitor) {
            int currentSize = sizeOf(root);
            if (currentSize == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }

            Node<T> existing = find(root, item, order);
            Node<T> updated = put(root, item, order);
            root = isRedRoot(updated) ? recolor(updated, false) : updated;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> existing = find(root, item, order);
            if (existing == null) {
                return false;
            }

            if (existing.count > 1) {
                root = decrement(root, item, order);
                return true;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, item, order);
            if (updated != null) {
                updated = recolor(updated, false);
            }
            root = updated;
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (monitor) {
            return sizeOf(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinctSizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            int totalSize = sizeOf(root);
            if (index < 0 || index >= totalSize) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining < leftSize + node.count) {
                    return node.key;
                } else {
                    remaining -= leftSize + node.count;
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
                int comparison = order.compare(item, node.key);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += sizeOf(node.left) + node.count;
                    node = node.right;
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(order, root);
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("item");
        }
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.subtreeSize;
    }

    private static int distinctSizeOf(Node<?> node) {
        return node == null ? 0 : node.subtreeDistinctSize;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static boolean isRedRoot(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        return new Node<E>(node.key, node.count, red, node.left, node.right);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.key);
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

    private static <E> Node<E> put(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, true, null, null);
        }

        int comparison = order.compare(item, node.key);
        if (comparison < 0) {
            node = new Node<E>(node.key, node.count, node.red,
                    put(node.left, item, order), node.right);
        } else if (comparison > 0) {
            node = new Node<E>(node.key, node.count, node.red,
                    node.left, put(node.right, item, order));
        } else {
            node = new Node<E>(node.key, node.count + 1, node.red,
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

    private static <E> Node<E> decrement(Node<E> node, E item, Order<E> order) {
        int comparison = order.compare(item, node.key);
        if (comparison < 0) {
            return new Node<E>(node.key, node.count, node.red,
                    decrement(node.left, item, order), node.right);
        }
        if (comparison > 0) {
            return new Node<E>(node.key, node.count, node.red,
                    node.left, decrement(node.right, item, order));
        }
        return new Node<E>(node.key, node.count - 1, node.red, node.left, node.right);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.key) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<E>(node.key, node.count, node.red,
                        delete(node.left, item, order), node.right);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            int comparison = order.compare(item, node.key);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }

                comparison = order.compare(item, node.key);
                if (comparison == 0) {
                    Node<E> successor = minimum(node.right);
                    node = new Node<E>(successor.key, successor.count, node.red,
                            node.left, deleteMinimum(node.right));
                } else {
                    node = new Node<E>(node.key, node.count, node.red,
                            node.left, delete(node.right, item, order));
                }
            }
        }
        return balance(node);
    }

    private static <E> Node<E> deleteMinimum(Node<E> node) {
        if (node.left == null) {
            return null;
        }

        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.key, node.count, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.key, node.count, node.red, node.left, right);
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

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> newLeft = new Node<E>(node.key, node.count, true,
                node.left, right.left);
        return new Node<E>(right.key, right.count, node.red, newLeft, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(node.key, node.count, true,
                left.right, node.right);
        return new Node<E>(left.key, left.count, node.red, left.left, newRight);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<E> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return new Node<E>(node.key, node.count, !node.red, left, right);
    }
}
