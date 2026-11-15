@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node<T> {
        final T item;
        final int count;
        final Node<T> left;
        final Node<T> right;
        final boolean red;
        final int total;
        final int distinct;

        Node(T item, int count, Node<T> left, Node<T> right, boolean red) {
            this.item = item;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }
    }

    public static final class Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;
        private final int size;
        private final int distinctSize;

        private Snapshot(Order<T> order, Node<T> root) {
            this.order = order;
            this.root = root;
            this.size = total(root);
            this.distinctSize = distinct(root);
        }

        public int size() {
            return size;
        }

        public int distinctSize() {
            return distinctSize;
        }

        public int count(T item) {
            requireNonNull(item);
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison == 0) {
                    return node.count;
                }
                node = comparison < 0 ? node.left : node.right;
            }
            return 0;
        }

        public T get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }

            Node<T> node = root;
            int position = index;
            while (node != null) {
                int leftTotal = total(node.left);
                if (position < leftTotal) {
                    node = node.left;
                } else if (position < leftTotal + node.count) {
                    return node.item;
                } else {
                    position -= leftTotal + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public void add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException();
            }
            Node<T> updated = insert(root, item);
            root = recolor(updated, false);
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> found = find(root, item);
            if (found == null) {
                return false;
            }

            if (found.count > 1) {
                root = decrement(root, item);
                return true;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, item);
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
            Node<T> found = find(root, item);
            return found == null ? 0 : found.count;
        }
    }

    public int size() {
        synchronized (monitor) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinct(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            int currentSize = total(root);
            if (index < 0 || index >= currentSize) {
                throw new IndexOutOfBoundsException();
            }

            Node<T> node = root;
            int position = index;
            while (node != null) {
                int leftTotal = total(node.left);
                if (position < leftTotal) {
                    node = node.left;
                } else if (position < leftTotal + node.count) {
                    return node.item;
                } else {
                    position -= leftTotal + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
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
                        return result + total(node.left);
                    }
                    node = node.left;
                } else {
                    result += total(node.left) + node.count;
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

    private Node<T> find(Node<T> node, T item) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.item, node.count, insert(node.left, item),
                    node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<T>(node.item, node.count, node.left,
                    insert(node.right, item), node.red);
        } else {
            updated = new Node<T>(node.item, node.count + 1, node.left,
                    node.right, node.red);
        }
        return fixUp(updated);
    }

    private Node<T> decrement(Node<T> node, T item) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<T>(node.item, node.count, decrement(node.left, item),
                    node.right, node.red);
        }
        if (comparison > 0) {
            return new Node<T>(node.item, node.count, node.left,
                    decrement(node.right, item), node.red);
        }
        return new Node<T>(node.item, node.count - 1, node.left, node.right, node.red);
    }

    private Node<T> delete(Node<T> node, T item) {
        Node<T> updated = node;

        if (order.compare(item, updated.item) < 0) {
            if (!isRed(updated.left) && !isRed(leftOf(updated.left))) {
                updated = moveRedLeft(updated);
            }
            updated = new Node<T>(updated.item, updated.count,
                    delete(updated.left, item), updated.right, updated.red);
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }

            int comparison = order.compare(item, updated.item);
            if (comparison == 0 && updated.right == null) {
                return null;
            }

            if (!isRed(updated.right) && !isRed(leftOf(updated.right))) {
                updated = moveRedRight(updated);
            }

            comparison = order.compare(item, updated.item);
            if (comparison == 0) {
                Node<T> successor = minimum(updated.right);
                updated = new Node<T>(successor.item, successor.count, updated.left,
                        deleteMinimum(updated.right), updated.red);
            } else {
                updated = new Node<T>(updated.item, updated.count, updated.left,
                        delete(updated.right, item), updated.red);
            }
        }
        return fixUp(updated);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        Node<T> updated = node;
        if (!isRed(updated.left) && !isRed(leftOf(updated.left))) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<T>(updated.item, updated.count,
                deleteMinimum(updated.left), updated.right, updated.red);
        return fixUp(updated);
    }

    private Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (updated.right != null && isRed(updated.right.left)) {
            Node<T> newRight = rotateRight(updated.right);
            updated = new Node<T>(updated.item, updated.count, updated.left,
                    newRight, updated.red);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (isRed(leftOf(updated.left))) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> fixUp(Node<T> node) {
        Node<T> updated = node;
        if (isRed(updated.right) && !isRed(updated.left)) {
            updated = rotateLeft(updated);
        }
        if (isRed(updated.left) && isRed(leftOf(updated.left))) {
            updated = rotateRight(updated);
        }
        if (isRed(updated.left) && isRed(updated.right)) {
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.item, node.count, node.left,
                right.left, true);
        return new Node<T>(right.item, right.count, newLeft, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.item, node.count, left.right,
                node.right, true);
        return new Node<T>(left.item, left.count, left.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, node.count, toggle(node.left), toggle(node.right),
                !node.red);
    }

    private Node<T> recolor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.count, node.left, node.right, red);
    }

    private static <T> Node<T> toggle(Node<T> node) {
        if (node == null) {
            return null;
        }
        return new Node<T>(node.item, node.count, node.left, node.right, !node.red);
    }

    private static <T> Node<T> leftOf(Node<T> node) {
        return node == null ? null : node.left;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static int total(Node<?> node) {
        return node == null ? 0 : node.total;
    }

    private static int distinct(Node<?> node) {
        return node == null ? 0 : node.distinct;
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }
}
