interface Order<T> {
    int compare(T left, T right);
}

@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    private static final class Node<E> {
        final E item;
        final int count;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int total;
        final int distinct;

        Node(E item, int count, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }
    }

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public void add(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            Node<T> updated = put(root, item, order);
            root = withColor(updated, BLACK);
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            if (found == null) {
                return false;
            }
            if (found.count > 1) {
                root = decrement(root, item, order);
                return true;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = withColor(updated, RED);
            }
            updated = delete(updated, item, order);
            root = updated == null ? null : withColor(updated, BLACK);
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
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
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison <= 0) {
                    current = current.left;
                } else {
                    result += total(current.left) + current.count;
                    current = current.right;
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

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;

        private Snapshot(Node<E> root, Order<E> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct(root);
        }

        public int count(E item) {
            requireItem(item);
            Node<E> found = find(root, item, order);
            return found == null ? 0 : found.count;
        }

        public E get(int index) {
            return select(root, index);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
    }

    private static int total(Node<?> node) {
        return node == null ? 0 : node.total;
    }

    private static int distinct(Node<?> node) {
        return node == null ? 0 : node.distinct;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> withColor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.count, node.left, node.right, red);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        Node<E> current = node;
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison < 0) {
                current = current.left;
            } else if (comparison > 0) {
                current = current.right;
            } else {
                return current;
            }
        }
        return null;
    }

    private static <E> Node<E> put(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, null, null, RED);
        }

        int comparison = order.compare(item, node.item);
        Node<E> updated;
        if (comparison < 0) {
            updated = new Node<E>(node.item, node.count,
                    put(node.left, item, order), node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<E>(node.item, node.count,
                    node.left, put(node.right, item, order), node.red);
        } else {
            updated = new Node<E>(node.item, node.count + 1,
                    node.left, node.right, node.red);
        }
        return fixUp(updated);
    }

    private static <E> Node<E> decrement(Node<E> node, E item, Order<E> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<E>(node.item, node.count,
                    decrement(node.left, item, order), node.right, node.red);
        }
        if (comparison > 0) {
            return new Node<E>(node.item, node.count,
                    node.left, decrement(node.right, item, order), node.red);
        }
        return new Node<E>(node.item, node.count - 1,
                node.left, node.right, node.red);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        Node<E> current = node;
        int comparison = order.compare(item, current.item);

        if (comparison < 0) {
            if (!isRed(current.left) && !isRed(current.left.left)) {
                current = moveRedLeft(current);
            }
            current = new Node<E>(current.item, current.count,
                    delete(current.left, item, order), current.right, current.red);
        } else {
            if (isRed(current.left)) {
                current = rotateRight(current);
            }

            comparison = order.compare(item, current.item);
            if (comparison == 0 && current.right == null) {
                return null;
            }

            if (!isRed(current.right) && !isRed(current.right.left)) {
                current = moveRedRight(current);
            }

            comparison = order.compare(item, current.item);
            if (comparison == 0) {
                Node<E> successor = minimum(current.right);
                current = new Node<E>(successor.item, successor.count,
                        current.left, deleteMinimum(current.right), current.red);
            } else {
                current = new Node<E>(current.item, current.count, current.left,
                        delete(current.right, item, order), current.red);
            }
        }
        return fixUp(current);
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
        Node<E> current = node;
        if (!isRed(current.left) && !isRed(current.left.left)) {
            current = moveRedLeft(current);
        }
        current = new Node<E>(current.item, current.count,
                deleteMinimum(current.left), current.right, current.red);
        return fixUp(current);
    }

    private static <E> Node<E> fixUp(Node<E> node) {
        Node<E> current = node;
        if (isRed(current.right) && !isRed(current.left)) {
            current = rotateLeft(current);
        }
        if (isRed(current.left) && isRed(current.left.left)) {
            current = rotateRight(current);
        }
        if (isRed(current.left) && isRed(current.right)) {
            current = flipColors(current);
        }
        return current;
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        Node<E> current = flipColors(node);
        if (isRed(current.right.left)) {
            Node<E> right = rotateRight(current.right);
            current = new Node<E>(current.item, current.count,
                    current.left, right, current.red);
            current = rotateLeft(current);
            current = flipColors(current);
        }
        return current;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        Node<E> current = flipColors(node);
        if (isRed(current.left.left)) {
            current = rotateRight(current);
            current = flipColors(current);
        }
        return current;
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> newLeft = new Node<E>(node.item, node.count,
                node.left, right.left, RED);
        return new Node<E>(right.item, right.count, newLeft,
                right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(node.item, node.count,
                left.right, node.right, RED);
        return new Node<E>(left.item, left.count, left.left,
                newRight, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = withColor(node.left, !node.left.red);
        Node<E> right = withColor(node.right, !node.right.red);
        return new Node<E>(node.item, node.count, left, right, !node.red);
    }

    private static <E> E select(Node<E> node, int index) {
        if (index < 0 || index >= total(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        Node<E> current = node;
        int remaining = index;
        while (current != null) {
            int leftTotal = total(current.left);
            if (remaining < leftTotal) {
                current = current.left;
            } else if (remaining < leftTotal + current.count) {
                return current.item;
            } else {
                remaining -= leftTotal + current.count;
                current = current.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }
}
