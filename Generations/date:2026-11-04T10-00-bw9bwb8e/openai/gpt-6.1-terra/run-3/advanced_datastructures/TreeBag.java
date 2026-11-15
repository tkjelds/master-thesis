interface Order<T> {
    int compare(T left, T right);
}

@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;
    private int distinct;

    private static final class Node<E> {
        final E item;
        final int count;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int total;

        Node(E item, int count, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.total = count + total(left) + total(right);
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
            if (total(root) == 2147483647) {
                throw new IllegalStateException("Size overflow");
            }
            boolean isNew = find(root, item, order) == null;
            Node<T> updated = insert(root, item, order);
            root = color(updated, false);
            if (isNew) {
                distinct++;
            }
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
            } else {
                Node<T> updated = root;
                if (!isRed(updated.left) && !isRed(updated.right)) {
                    updated = color(updated, true);
                }
                updated = delete(updated, item, order);
                if (updated != null) {
                    updated = color(updated, false);
                }
                root = updated;
                distinct--;
            }
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
            return distinct;
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
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison <= 0) {
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
            return new Snapshot<T>(root, distinct, order, monitor);
        }
    }

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final int distinct;
        private final Order<E> order;
        private final Object monitor;

        private Snapshot(Node<E> root, int distinct, Order<E> order, Object monitor) {
            this.root = root;
            this.distinct = distinct;
            this.order = order;
            this.monitor = monitor;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct;
        }

        public int count(E item) {
            requireItem(item);
            // Serialize comparisons with live operations and other snapshots.
            synchronized (monitor) {
                Node<E> found = find(root, item, order);
                return found == null ? 0 : found.count;
            }
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

    private static <E> int total(Node<E> node) {
        return node == null ? 0 : node.total;
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> color(Node<E> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.count, red, node.left, node.right);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <E> E select(Node<E> node, int index) {
        if (index < 0 || index >= total(node)) {
            throw new IndexOutOfBoundsException();
        }
        while (node != null) {
            int leftSize = total(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index - leftSize < node.count) {
                return node.item;
            } else {
                index -= leftSize + node.count;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException();
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> newLeft = new Node<E>(
                node.item, node.count, true, node.left, right.left);
        return new Node<E>(
                right.item, right.count, node.red, newLeft, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(
                node.item, node.count, true, left.right, node.right);
        return new Node<E>(
                left.item, left.count, node.red, left.left, newRight);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = color(node.left, !node.left.red);
        Node<E> right = color(node.right, !node.right.red);
        return new Node<E>(node.item, node.count, !node.red, left, right);
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, true, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<E>(node.item, node.count, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            node = new Node<E>(node.item, node.count, node.red,
                    node.left, insert(node.right, item, order));
        } else {
            node = new Node<E>(node.item, node.count + 1, node.red,
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
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<E>(node.item, node.count, node.red,
                    decrement(node.left, item, order), node.right);
        }
        if (comparison > 0) {
            return new Node<E>(node.item, node.count, node.red,
                    node.left, decrement(node.right, item, order));
        }
        return new Node<E>(node.item, node.count - 1, node.red,
                node.left, node.right);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.item, node.count, node.red, node.left, right);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.left.left)) {
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
        node = new Node<E>(node.item, node.count, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<E>(node.item, node.count, node.red,
                    delete(node.left, item, order), node.right);
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
                Node<E> successor = minimum(node.right);
                node = new Node<E>(successor.item, successor.count, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = new Node<E>(node.item, node.count, node.red,
                        node.left, delete(node.right, item, order));
            }
        }
        return balance(node);
    }
}
