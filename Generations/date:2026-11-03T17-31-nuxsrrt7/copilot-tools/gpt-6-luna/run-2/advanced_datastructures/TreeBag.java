@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    public static final class Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;

        private Snapshot(Order<T> order, Node<T> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return nodeSize(root);
        }

        public int distinctSize() {
            return nodeDistinctSize(root);
        }

        public int count(T item) {
            requireNonNull(item, "item");
            Node<T> current = root;
            while (current != null) {
                int comparison = compare(order, item, current.item);
                if (comparison == 0) {
                    return current.count;
                }
                current = comparison < 0 ? current.left : current.right;
            }
            return 0;
        }

        public T get(int index) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = nodeSize(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining < leftSize + current.count) {
                    return current.item;
                } else {
                    remaining -= leftSize + current.count;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        this.order = requireNonNull(order, "order");
    }

    public void add(T item) {
        requireNonNull(item, "item");
        synchronized (this) {
            if (nodeSize(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            root = insert(root, item);
            root = recolor(root, BLACK);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item, "item");
        synchronized (this) {
            Node<T> found = find(root, item);
            if (found == null) {
                return false;
            }

            if (found.count > 1) {
                root = setCount(root, item, found.count - 1);
            } else {
                if (!isRed(root.left) && !isRed(root.right)) {
                    root = recolor(root, RED);
                }
                root = delete(root, item);
                if (root != null) {
                    root = recolor(root, BLACK);
                }
            }
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item, "item");
        synchronized (this) {
            Node<T> found = find(root, item);
            return found == null ? 0 : found.count;
        }
    }

    public int size() {
        synchronized (this) {
            return nodeSize(root);
        }
    }

    public int distinctSize() {
        synchronized (this) {
            return nodeDistinctSize(root);
        }
    }

    public T get(int index) {
        synchronized (this) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = nodeSize(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining < leftSize + current.count) {
                    return current.item;
                } else {
                    remaining -= leftSize + current.count;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(T item) {
        requireNonNull(item, "item");
        synchronized (this) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = compare(order, item, current.item);
                if (comparison <= 0) {
                    current = current.left;
                } else {
                    result += nodeSize(current.left) + current.count;
                    current = current.right;
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (this) {
            return new Snapshot<T>(order, root);
        }
    }

    private Node<T> find(Node<T> node, T item) {
        Node<T> current = node;
        while (current != null) {
            int comparison = compare(order, item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, null, null, RED);
        }

        int comparison = compare(order, item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = withLeft(node, insert(node.left, item));
        } else if (comparison > 0) {
            updated = withRight(node, insert(node.right, item));
        } else {
            updated = new Node<T>(node.item, node.count + 1,
                    node.left, node.right, node.red);
        }
        return fixAfterInsert(updated);
    }

    private Node<T> setCount(Node<T> node, T item, int count) {
        int comparison = compare(order, item, node.item);
        if (comparison < 0) {
            return withLeft(node, setCount(node.left, item, count));
        }
        if (comparison > 0) {
            return withRight(node, setCount(node.right, item, count));
        }
        return new Node<T>(node.item, count, node.left, node.right, node.red);
    }

    private Node<T> delete(Node<T> node, T item) {
        Node<T> updated = node;
        int comparison = compare(order, item, updated.item);

        if (comparison < 0) {
            if (updated.left != null) {
                if (!isRed(updated.left) && !isRed(updated.left.left)) {
                    updated = moveRedLeft(updated);
                }
                updated = withLeft(updated, delete(updated.left, item));
            }
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }
            comparison = compare(order, item, updated.item);
            if (comparison == 0 && updated.right == null) {
                return null;
            }
            if (updated.right != null) {
                if (!isRed(updated.right) && !isRed(updated.right.left)) {
                    updated = moveRedRight(updated);
                }
                comparison = compare(order, item, updated.item);
                if (comparison == 0) {
                    Node<T> successor = minimum(updated.right);
                    updated = new Node<T>(successor.item, successor.count,
                            updated.left, deleteMin(updated.right), updated.red);
                } else {
                    updated = withRight(updated, delete(updated.right, item));
                }
            }
        }
        return fixAfterDelete(updated);
    }

    private Node<T> deleteMin(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        Node<T> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = withLeft(updated, deleteMin(updated.left));
        return fixAfterDelete(updated);
    }

    private Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> fixAfterInsert(Node<T> node) {
        Node<T> updated = node;
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

    private Node<T> fixAfterDelete(Node<T> node) {
        if (node == null) {
            return null;
        }
        Node<T> updated = node;
        if (isRed(updated.right)) {
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

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (updated.right != null && isRed(updated.right.left)) {
            updated = withRight(updated, rotateRight(updated.right));
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (updated.left != null && isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.item, node.count,
                node.left, right.left, RED);
        return new Node<T>(right.item, right.count, newLeft,
                right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.item, node.count,
                left.right, node.right, RED);
        return new Node<T>(left.item, left.count, left.left,
                newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<T> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return new Node<T>(node.item, node.count, left, right, !node.red);
    }

    private Node<T> withLeft(Node<T> node, Node<T> left) {
        return new Node<T>(node.item, node.count, left, node.right, node.red);
    }

    private Node<T> withRight(Node<T> node, Node<T> right) {
        return new Node<T>(node.item, node.count, node.left, right, node.red);
    }

    private Node<T> recolor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.count, node.left, node.right, red);
    }

    private boolean isRed(Node<T> node) {
        return node != null && node.red == RED;
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.subtreeSize;
    }

    private static int nodeDistinctSize(Node<?> node) {
        return node == null ? 0 : node.subtreeDistinctSize;
    }

    private static int compare(Order<?> order, Object left, Object right) {
        return compareTyped(order, left, right);
    }

    private static <E> int compareTyped(Order<E> order, Object left, Object right) {
        return order.compare(castElement(left), castElement(right));
    }

    private static <E> E castElement(Object item) {
        return (E) item;
    }

    private static <E> E requireNonNull(E value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }

    private static final class Node<E> {
        private final E item;
        private final int count;
        private final Node<E> left;
        private final Node<E> right;
        private final boolean red;
        private final int subtreeSize;
        private final int subtreeDistinctSize;

        private Node(E item, int count, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.subtreeSize = nodeSize(left) + count + nodeSize(right);
            this.subtreeDistinctSize = nodeDistinctSize(left) + 1 + nodeDistinctSize(right);
        }
    }
}
