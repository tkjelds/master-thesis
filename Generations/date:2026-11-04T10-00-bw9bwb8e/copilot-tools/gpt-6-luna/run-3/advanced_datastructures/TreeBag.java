@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;

        private Snapshot(Order<E> order, Node<E> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return totalSize(root);
        }

        public int distinctSize() {
            return distinctSize(root);
        }

        public int count(E item) {
            requireNonNull(item);
            Node<E> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public E get(int index) {
            int size = totalSize(root);
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<E> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = totalSize(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining < leftSize + node.count) {
                    return node.item;
                } else {
                    remaining -= leftSize + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static final class Node<E> {
        final E item;
        final int count;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int totalSize;
        final int distinctSize;

        Node(E item, int count, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.totalSize = totalSize(left) + count + totalSize(right);
            this.distinctSize = distinctSize(left) + 1 + distinctSize(right);
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
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
            if (totalSize(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            Node<T> updated = insert(root, item, order);
            root = recolor(updated, false);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            if (found == null) {
                return false;
            }

            if (found.count > 1) {
                root = decrement(root, item, order);
            } else {
                Node<T> updated = root;
                if (!isRed(updated.left) && !isRed(updated.right)) {
                    updated = recolor(updated, true);
                }
                updated = delete(updated, item, order);
                root = updated == null ? null : recolor(updated, false);
            }
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
            return totalSize(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinctSize(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            int size = totalSize(root);
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = totalSize(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining < leftSize + node.count) {
                    return node.item;
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
                int comparison = order.compare(item, node.item);
                if (comparison <= 0) {
                    if (comparison == 0) {
                        result += totalSize(node.left);
                        break;
                    }
                    node = node.left;
                } else {
                    result += totalSize(node.left) + node.count;
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

    private static int totalSize(Node<?> node) {
        return node == null ? 0 : node.totalSize;
    }

    private static int distinctSize(Node<?> node) {
        return node == null ? 0 : node.distinctSize;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.count, red, node.left, node.right);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
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

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, true, null, null);
        }

        int comparison = order.compare(item, node.item);
        Node<E> updated;
        if (comparison < 0) {
            updated = new Node<E>(node.item, node.count, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            updated = new Node<E>(node.item, node.count, node.red,
                    node.left, insert(node.right, item, order));
        } else {
            updated = new Node<E>(node.item, node.count + 1, node.red,
                    node.left, node.right);
        }
        return fixUp(updated);
    }

    private static <E> Node<E> decrement(Node<E> node, E item, Order<E> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<E>(node.item, node.count, node.red,
                    decrement(node.left, item, order), node.right);
        }
        if (comparison > 0) {
            return new Node<E>(node.item, node.count, node.red,
                    node.left, decrement(node.right, item, order));
        }
        return new Node<E>(node.item, node.count - 1, node.red, node.left, node.right);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return null;
        }

        Node<E> updated = node;
        int comparison = order.compare(item, updated.item);
        if (comparison < 0) {
            if (!isRed(updated.left) && !isRed(updated.left == null ? null : updated.left.left)) {
                updated = moveRedLeft(updated);
            }
            updated = new Node<E>(updated.item, updated.count, updated.red,
                    delete(updated.left, item, order), updated.right);
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }

            comparison = order.compare(item, updated.item);
            if (comparison == 0 && updated.right == null) {
                return null;
            }

            if (!isRed(updated.right)
                    && !isRed(updated.right == null ? null : updated.right.left)) {
                updated = moveRedRight(updated);
            }

            comparison = order.compare(item, updated.item);
            if (comparison == 0) {
                Node<E> successor = minimum(updated.right);
                updated = new Node<E>(successor.item, successor.count, updated.red,
                        updated.left, deleteMinimum(updated.right));
            } else {
                updated = new Node<E>(updated.item, updated.count, updated.red,
                        updated.left, delete(updated.right, item, order));
            }
        }
        return fixUp(updated);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        Node<E> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <E> Node<E> deleteMinimum(Node<E> node) {
        if (node.left == null) {
            return null;
        }

        Node<E> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<E>(updated.item, updated.count, updated.red,
                deleteMinimum(updated.left), updated.right);
        return fixUp(updated);
    }

    private static <E> Node<E> fixUp(Node<E> node) {
        Node<E> updated = node;
        if (isRed(updated.right) && !isRed(updated.left)) {
            updated = rotateLeft(updated);
        }
        if (isRed(updated.left) && isRed(updated.left.left)) {
            updated = rotateRight(updated);
        }
        if (isRed(updated.left) && isRed(updated.right)) {
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (updated.right != null && isRed(updated.right.left)) {
            Node<E> right = rotateRight(updated.right);
            updated = new Node<E>(updated.item, updated.count, updated.red, updated.left, right);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (updated.left != null && isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, node.count, !node.red,
                recolor(node.left, !node.left.red),
                recolor(node.right, !node.right.red));
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, node.count, true, node.left, right.left);
        return new Node<E>(right.item, right.count, node.red, left, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, node.count, true, left.right, node.right);
        return new Node<E>(left.item, left.count, node.red, left.left, right);
    }
}
