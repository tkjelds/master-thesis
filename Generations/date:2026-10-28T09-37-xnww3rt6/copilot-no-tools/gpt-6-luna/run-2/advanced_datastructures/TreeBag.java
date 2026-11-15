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
    private int distinctSize;

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

            boolean alreadyPresent = find(root, item, order) != null;
            Node<T> newRoot = insert(root, item, order);
            root = recolor(newRoot, BLACK);
            if (!alreadyPresent) {
                distinctSize++;
            }
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> found = find(root, item, order);
            if (found == null) {
                return false;
            }

            Node<T> newRoot;
            if (found.count > 1) {
                newRoot = setCount(root, item, found.count - 1, order);
            } else {
                newRoot = root;
                if (!isRed(newRoot.left) && !isRed(newRoot.right)) {
                    newRoot = recolor(newRoot, RED);
                }
                newRoot = delete(newRoot, item, order);
                if (newRoot != null) {
                    newRoot = recolor(newRoot, BLACK);
                }
                distinctSize--;
            }

            root = newRoot;
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
            return distinctSize;
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return rank(root, item, order);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(order, root, distinctSize);
        }
    }

    public static final class Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;
        private final int distinctSize;

        private Snapshot(Order<T> order, Node<T> root, int distinctSize) {
            this.order = order;
            this.root = root;
            this.distinctSize = distinctSize;
        }

        public int size() {
            return sizeOf(root);
        }

        public int distinctSize() {
            return distinctSize;
        }

        public int count(T item) {
            requireNonNull(item);
            Node<T> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public T get(int index) {
            return select(root, index);
        }
    }

    private static final class Node<T> {
        private final T item;
        private final int count;
        private final boolean red;
        private final Node<T> left;
        private final Node<T> right;
        private final int total;

        private Node(T item, int count, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.total = sizeOf(left) + count + sizeOf(right);
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("item");
        }
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.total;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.count, red, node.left, node.right);
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

    private static <T> Node<T> insert(Node<T> node, T item, Order<T> order) {
        if (node == null) {
            return new Node<T>(item, 1, RED, null, null);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.item, node.count, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            updated = new Node<T>(node.item, node.count, node.red,
                    node.left, insert(node.right, item, order));
        } else {
            updated = new Node<T>(node.item, node.count + 1, node.red,
                    node.left, node.right);
        }
        return fixUp(updated);
    }

    private static <T> Node<T> setCount(Node<T> node, T item, int count, Order<T> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<T>(node.item, node.count, node.red,
                    setCount(node.left, item, count, order), node.right);
        }
        if (comparison > 0) {
            return new Node<T>(node.item, node.count, node.red,
                    node.left, setCount(node.right, item, count, order));
        }
        return new Node<T>(node.item, count, node.red, node.left, node.right);
    }

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.item, node.count, RED, node.left, right.left);
        return new Node<T>(right.item, right.count, node.red, newLeft, right.right);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.item, node.count, RED, left.right, node.right);
        return new Node<T>(left.item, left.count, node.red, left.left, newRight);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, node.count, !node.red,
                recolor(node.left, !isRed(node.left)),
                recolor(node.right, !isRed(node.right)));
    }

    private static <T> Node<T> fixUp(Node<T> node) {
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

    private static <T> Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<T> newRight = rotateRight(node.right);
            node = new Node<T>(node.item, node.count, node.red, node.left, newRight);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <T> Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (node.left != null && isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <T> Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <T> Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<T>(node.item, node.count, node.red,
                deleteMinimum(node.left), node.right);
        return fixUp(node);
    }

    private static <T> Node<T> delete(Node<T> node, T item, Order<T> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            if (node.left != null && !isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.item, node.count, node.red,
                    delete(node.left, item, order), node.right);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            comparison = order.compare(item, node.item);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (node.right != null && !isRed(node.right) && !isRed(node.right.left)) {
                node = moveRedRight(node);
            }

            comparison = order.compare(item, node.item);
            if (comparison == 0) {
                Node<T> successor = minimum(node.right);
                node = new Node<T>(successor.item, successor.count, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = new Node<T>(node.item, node.count, node.red,
                        node.left, delete(node.right, item, order));
            }
        }
        return fixUp(node);
    }

    private static <T> T select(Node<T> node, int index) {
        int size = sizeOf(node);
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }

        int remaining = index;
        while (node != null) {
            int leftSize = sizeOf(node.left);
            if (remaining < leftSize) {
                node = node.left;
            } else if (remaining < leftSize + node.count) {
                return node.item;
            } else {
                remaining -= leftSize + node.count;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
    }

    private static <T> int rank(Node<T> node, T item, Order<T> order) {
        int result = 0;
        while (node != null) {
            int comparison = order.compare(item, node.item);
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
