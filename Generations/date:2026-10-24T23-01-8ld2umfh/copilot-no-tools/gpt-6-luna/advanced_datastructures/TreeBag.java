@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    @FunctionalInterface
    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E item;
        final int count;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int subtreeSize;
        final int subtreeDistinctSize;

        Node(E item, int count, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.subtreeSize = sizeOf(left) + count + sizeOf(right);
            this.subtreeDistinctSize =
                    distinctSizeOf(left) + 1 + distinctSizeOf(right);
        }
    }

    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;
        private final int size;
        private final int distinctSize;

        private Snapshot(Order<E> order, Node<E> root, int size, int distinctSize) {
            this.order = order;
            this.root = root;
            this.size = size;
            this.distinctSize = distinctSize;
        }

        public int size() {
            return size;
        }

        public int distinctSize() {
            return distinctSize;
        }

        public int count(E item) {
            requireNonNull(item, "item");
            return countOf(root, item, order);
        }

        public E get(int index) {
            return itemAt(root, index, size);
        }
    }

    private final Object mutex = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int size;
    private int distinctSize;

    public TreeBag(Order<T> order) {
        this.order = requireNonNull(order, "order");
    }

    public void add(T item) {
        requireNonNull(item, "item");
        synchronized (mutex) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            int oldCount = countOf(root, item, order);
            Node<T> updatedRoot = insert(root, item, order);
            root = paint(updatedRoot, false);
            size++;
            if (oldCount == 0) {
                distinctSize++;
            }
        }
    }

    public boolean remove(T item) {
        requireNonNull(item, "item");
        synchronized (mutex) {
            int oldCount = countOf(root, item, order);
            if (oldCount == 0) {
                return false;
            }

            if (oldCount > 1) {
                root = changeCount(root, item, oldCount - 1, order);
            } else {
                Node<T> updatedRoot = root;
                if (!isRed(updatedRoot.left) && !isRed(updatedRoot.right)) {
                    updatedRoot = paint(updatedRoot, true);
                }
                updatedRoot = delete(updatedRoot, item, order);
                root = updatedRoot == null ? null : paint(updatedRoot, false);
                distinctSize--;
            }
            size--;
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item, "item");
        synchronized (mutex) {
            return countOf(root, item, order);
        }
    }

    public int size() {
        synchronized (mutex) {
            return size;
        }
    }

    public int distinctSize() {
        synchronized (mutex) {
            return distinctSize;
        }
    }

    public T get(int index) {
        synchronized (mutex) {
            return itemAt(root, index, size);
        }
    }

    public int rank(T item) {
        requireNonNull(item, "item");
        synchronized (mutex) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += sizeOf(node.left) + node.count;
                    node = node.right;
                } else {
                    return result + sizeOf(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (mutex) {
            return new Snapshot<>(order, root, size, distinctSize);
        }
    }

    private static <E> int countOf(Node<E> root, E item, Order<E> order) {
        Node<E> node = root;
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return node.count;
            }
        }
        return 0;
    }

    private static <E> E itemAt(Node<E> root, int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "index: " + index + ", size: " + size);
        }
        Node<E> node = root;
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

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.subtreeSize;
    }

    private static int distinctSizeOf(Node<?> node) {
        return node == null ? 0 : node.subtreeDistinctSize;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> paint(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<>(node.item, node.count, node.left, node.right, red);
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<>(item, 1, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<E> updated;
        if (comparison < 0) {
            updated = new Node<>(node.item, node.count,
                    insert(node.left, item, order), node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<>(node.item, node.count,
                    node.left, insert(node.right, item, order), node.red);
        } else {
            updated = new Node<>(node.item, node.count + 1,
                    node.left, node.right, node.red);
        }

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

    private static <E> Node<E> changeCount(
            Node<E> node, E item, int count, Order<E> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<>(node.item, node.count,
                    changeCount(node.left, item, count, order),
                    node.right, node.red);
        }
        if (comparison > 0) {
            return new Node<>(node.item, node.count, node.left,
                    changeCount(node.right, item, count, order), node.red);
        }
        return new Node<>(node.item, count, node.left, node.right, node.red);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.item) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<>(node.item, node.count,
                        delete(node.left, item, order), node.right, node.red);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            if (order.compare(item, node.item) == 0 && node.right == null) {
                return null;
            }

            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }
                if (order.compare(item, node.item) == 0) {
                    Node<E> successor = minimum(node.right);
                    node = new Node<>(successor.item, successor.count,
                            node.left, deleteMinimum(node.right), node.red);
                } else {
                    node = new Node<>(node.item, node.count, node.left,
                            delete(node.right, item, order), node.red);
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
        node = new Node<>(node.item, node.count,
                deleteMinimum(node.left), node.right, node.red);
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
            Node<E> newRight = rotateRight(node.right);
            node = new Node<>(node.item, node.count, node.left, newRight, node.red);
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
        Node<E> newLeft = new Node<>(node.item, node.count,
                node.left, right.left, true);
        return new Node<>(right.item, right.count, newLeft, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<>(node.item, node.count,
                left.right, node.right, true);
        return new Node<>(left.item, left.count, left.left, newRight, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<>(node.item, node.count,
                toggleColor(node.left), toggleColor(node.right), !node.red);
    }

    private static <E> Node<E> toggleColor(Node<E> node) {
        return node == null ? null
                : new Node<>(node.item, node.count, node.left, node.right, !node.red);
    }

    private static <E> E requireNonNull(E value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        return value;
    }
}
