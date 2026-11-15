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
        final int size;
        final int distinctSize;

        Node(T item, int count, Node<T> left, Node<T> right, boolean red) {
            this.item = item;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = sizeOf(left) + count + sizeOf(right);
            this.distinctSize = distinctSizeOf(left) + 1 + distinctSizeOf(right);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (sizeOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            Node<T> updated = insert(root, item);
            root = recolor(updated, false);
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> found = find(root, item);
            if (found == null) {
                return false;
            }

            Node<T> updated;
            if (found.count > 1) {
                updated = changeCount(root, item, -1);
            } else {
                Node<T> start = root;
                if (!isRed(start.left) && !isRed(start.right)) {
                    start = recolor(start, true);
                }
                updated = delete(start, item);
                if (updated != null) {
                    updated = recolor(updated, false);
                }
            }
            root = updated;
            return true;
        }
    }

    public int count(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> node = find(root, item);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return distinctSizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return getFrom(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return rankFrom(root, item);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<T>(order, root);
        }
    }

    public static final class Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;

        private Snapshot(Order<T> order, Node<T> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return sizeOf(root);
        }

        public int distinctSize() {
            return distinctSizeOf(root);
        }

        public int count(T item) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            Node<T> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public T get(int index) {
            return getFrom(root, index);
        }
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = withLeft(node, insert(node.left, item));
        } else if (comparison > 0) {
            updated = withRight(node, insert(node.right, item));
        } else {
            updated = new Node<T>(node.item, node.count + 1, node.left, node.right, node.red);
        }
        return balanceAfterInsert(updated);
    }

    private Node<T> changeCount(Node<T> node, T item, int delta) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return withLeft(node, changeCount(node.left, item, delta));
        }
        if (comparison > 0) {
            return withRight(node, changeCount(node.right, item, delta));
        }
        return new Node<T>(node.item, node.count + delta, node.left, node.right, node.red);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = withLeft(node, delete(node.left, item));
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            int comparison = order.compare(item, node.item);
            if (comparison == 0 && node.right == null) {
                return null;
            }
            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }
                comparison = order.compare(item, node.item);
                if (comparison == 0) {
                    Node<T> successor = minimum(node.right);
                    node = new Node<T>(successor.item, successor.count, node.left,
                            deleteMinimum(node.right), node.red);
                } else {
                    node = withRight(node, delete(node.right, item));
                }
            }
        }
        return balanceAfterDelete(node);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = withLeft(node, deleteMinimum(node.left));
        return balanceAfterDelete(node);
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

    private Node<T> balanceAfterInsert(Node<T> node) {
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

    private Node<T> balanceAfterDelete(Node<T> node) {
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
        Node<T> newLeft = new Node<T>(node.item, node.count, node.left, right.left, true);
        return new Node<T>(right.item, right.count, newLeft, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.item, node.count, left.right, node.right, true);
        return new Node<T>(left.item, left.count, left.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, node.count, recolor(node.left, !node.left.red),
                recolor(node.right, !node.right.red), !node.red);
    }

    private Node<T> withLeft(Node<T> node, Node<T> left) {
        return new Node<T>(node.item, node.count, left, node.right, node.red);
    }

    private Node<T> withRight(Node<T> node, Node<T> right) {
        return new Node<T>(node.item, node.count, node.left, right, node.red);
    }

    private Node<T> find(Node<T> node, T item) {
        return find(node, item, order);
    }

    private static <T> Node<T> find(Node<T> node, T item, Order<T> order) {
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

    private int rankFrom(Node<T> node, T item) {
        int rank = 0;
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                rank += sizeOf(node.left) + node.count;
                node = node.right;
            } else {
                return rank + sizeOf(node.left);
            }
        }
        return rank;
    }

    private static <T> T getFrom(Node<T> node, int index) {
        if (index < 0 || index >= sizeOf(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index < leftSize + node.count) {
                return node.item;
            } else {
                index -= leftSize + node.count;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <T> Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        return node == null || node.red == red
                ? node
                : new Node<T>(node.item, node.count, node.left, node.right, red);
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static int distinctSizeOf(Node<?> node) {
        return node == null ? 0 : node.distinctSize;
    }
}
