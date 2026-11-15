@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E value;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E value, Node<E> left, Node<E> right, boolean red) {
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.count = 1 + sizeOf(left) + sizeOf(right);
        }
    }

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;

        private Snapshot(Node<E> root, Order<E> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return sizeOf(root);
        }

        public E get(int index) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException();
            }
            return getAt(root, index);
        }

        public boolean contains(E item) {
            requireNonNull(item, "item");
            return find(root, item, order) != null;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeSet(Order<T> order) {
        this.order = requireNonNull(order, "order");
    }

    public boolean add(T item) {
        requireNonNull(item, "item");
        synchronized (monitor) {
            if (find(root, item, order) != null) {
                return false;
            }
            Node<T> updated = insert(root, item, order);
            root = withColor(updated, false);
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item, "item");
        synchronized (monitor) {
            if (find(root, item, order) == null) {
                return false;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, true);
            }
            updated = delete(updated, item, order);
            root = updated == null ? null : withColor(updated, false);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item, "item");
        synchronized (monitor) {
            return find(root, item, order) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException();
            }
            return getAt(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item, "item");
        synchronized (monitor) {
            int rank = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.value);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    rank += sizeOf(node.left) + 1;
                    node = node.right;
                } else {
                    return rank + sizeOf(node.left);
                }
            }
            return rank;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order);
        }
    }

    private static <E> E requireNonNull(E value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> withColor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.value, node.left, node.right, red);
    }

    private static <E> Node<E> toggleColor(Node<E> node) {
        return node == null ? null : withColor(node, !node.red);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.value, node.left, right.left, true);
        return new Node<E>(right.value, left, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.value, left.right, node.right, true);
        return new Node<E>(left.value, left.left, right, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(
                node.value,
                toggleColor(node.left),
                toggleColor(node.right),
                !node.red);
    }

    private static <E> Node<E> fixUp(Node<E> node) {
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

    private static <E> Node<E> insert(Node<E> node, E value, Order<E> order) {
        if (node == null) {
            return new Node<E>(value, null, null, true);
        }

        int comparison = order.compare(value, node.value);
        if (comparison < 0) {
            node = new Node<E>(node.value, insert(node.left, value, order), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<E>(node.value, node.left, insert(node.right, value, order), node.red);
        }
        return fixUp(node);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.value, node.left, right, node.red);
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

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.value, deleteMin(node.left), node.right, node.red);
        return fixUp(node);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <E> Node<E> delete(Node<E> node, E value, Order<E> order) {
        if (order.compare(value, node.value) < 0) {
            if (!isRed(node.left) && (node.left == null || !isRed(node.left.left))) {
                node = moveRedLeft(node);
            }
            node = new Node<E>(node.value, delete(node.left, value, order), node.right, node.red);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            int comparison = order.compare(value, node.value);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (!isRed(node.right) && (node.right == null || !isRed(node.right.left))) {
                node = moveRedRight(node);
            }

            comparison = order.compare(value, node.value);
            if (comparison == 0) {
                Node<E> successor = minimum(node.right);
                node = new Node<E>(
                        successor.value,
                        node.left,
                        deleteMin(node.right),
                        node.red);
            } else {
                node = new Node<E>(
                        node.value,
                        node.left,
                        delete(node.right, value, order),
                        node.red);
            }
        }
        return fixUp(node);
    }

    private static <E> Node<E> find(Node<E> node, E value, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(value, node.value);
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

    private static <E> E getAt(Node<E> node, int index) {
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index > leftSize) {
                index -= leftSize + 1;
                node = node.right;
            } else {
                return node.value;
            }
        }
        throw new IndexOutOfBoundsException();
    }
}
