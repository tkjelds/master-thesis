interface Order<T> {
    int compare(T left, T right);
}

@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

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

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int distinct;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    // Operations serialize on the monitor; none waits for capacity or elements.
    public void add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (total(root) == 2147483647) {
                throw new IllegalStateException("size overflow");
            }
            Node<T> existing = find(root, item, order);
            Node<T> updated = insert(root, item, order);
            root = withColor(updated, BLACK);
            if (existing == null) {
                distinct++;
            }
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
            Node<T> existing = find(root, item, order);
            if (existing == null) {
                return false;
            }
            if (existing.count > 1) {
                root = decrement(root, item, order);
            } else {
                Node<T> updated = root;
                if (!isRed(updated.left) && !isRed(updated.right)) {
                    updated = withColor(updated, RED);
                }
                updated = delete(updated, item, order);
                root = updated == null ? null : withColor(updated, BLACK);
                distinct--;
            }
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (lock) {
            Node<T> found = find(root, item, order);
            return found == null ? 0 : found.count;
        }
    }

    public int size() {
        synchronized (lock) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return distinct;
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (lock) {
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
        synchronized (lock) {
            return new Snapshot<T>(root, distinct, order, lock);
        }
    }

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final int distinct;
        private final Order<E> order;
        private final Object lock;

        private Snapshot(Node<E> root, int distinct, Order<E> order, Object lock) {
            this.root = root;
            this.distinct = distinct;
            this.order = order;
            this.lock = lock;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct;
        }

        public int count(E item) {
            requireItem(item);
            synchronized (lock) {
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
            throw new NullPointerException("item");
        }
    }

    private static <E> int total(Node<E> node) {
        return node == null ? 0 : node.total;
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> withColor(Node<E> node, boolean red) {
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
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int left = total(node.left);
            if (index < left) {
                node = node.left;
            } else if (index - left < node.count) {
                return node.item;
            } else {
                index -= left + node.count;
                node = node.right;
            }
        }
        throw new AssertionError("invalid subtree totals");
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, node.count, RED, node.left, right.left);
        return new Node<E>(right.item, right.count, node.red, left, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, node.count, RED, left.right, node.right);
        return new Node<E>(left.item, left.count, node.red, left.left, right);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, node.count, !node.red,
                withColor(node.left, !node.left.red),
                withColor(node.right, !node.right.red));
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, RED, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<E>(node.item, node.count, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            node = new Node<E>(node.item, node.count, node.red,
                    node.left, insert(node.right, item, order));
        } else {
            node = new Node<E>(node.item, node.count + 1, node.red, node.left, node.right);
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
        return new Node<E>(node.item, node.count - 1, node.red, node.left, node.right);
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

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.item, node.count, node.red,
                deleteMin(node.left), node.right);
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
                Node<E> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                node = new Node<E>(successor.item, successor.count, node.red,
                        node.left, deleteMin(node.right));
            } else {
                node = new Node<E>(node.item, node.count, node.red, node.left,
                        delete(node.right, item, order));
            }
        }
        return balance(node);
    }
}
