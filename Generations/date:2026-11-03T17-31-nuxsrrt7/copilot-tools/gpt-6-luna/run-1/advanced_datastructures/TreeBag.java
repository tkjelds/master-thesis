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
    private int size;
    private int distinctSize;

    private static final class Node<T> {
        final T item;
        final int count;
        final boolean red;
        final Node<T> left;
        final Node<T> right;
        final int subtreeSize;

        Node(T item, int count, boolean red, Node<T> left, Node<T> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.subtreeSize = subtreeSize(left) + count + subtreeSize(right);
        }
    }

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public void add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }

            Node<T> existing = find(root, item);
            root = insert(root, item);
            root = withColor(root, BLACK);
            size++;
            if (existing == null) {
                distinctSize++;
            }
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> existing = find(root, item);
            if (existing == null) {
                return false;
            }

            if (existing.count > 1) {
                root = changeCount(root, item, existing.count - 1);
            } else {
                if (!isRed(root.left) && !isRed(root.right)) {
                    root = withColor(root, RED);
                }
                root = delete(root, item);
                if (root != null) {
                    root = withColor(root, BLACK);
                }
                distinctSize--;
            }
            size--;
            return true;
        }
    }

    public int count(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            Node<T> node = find(root, item);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinctSize;
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = subtreeSize(node.left);
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
                    node = node.left;
                } else {
                    result += subtreeSize(node.left) + node.count;
                    node = node.right;
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(order, root, size, distinctSize);
        }
    }

    public static final class Snapshot<T> {
        private final Order<T> order;
        private final Node<T> root;
        private final int size;
        private final int distinctSize;

        private Snapshot(Order<T> order, Node<T> root, int size, int distinctSize) {
            this.order = order;
            this.root = root;
            this.size = size;
            this.distinctSize = distinctSize;
        }

        public int size() {
            synchronized (this) {
                return size;
            }
        }

        public int distinctSize() {
            synchronized (this) {
                return distinctSize;
            }
        }

        public int count(T item) {
            requireNonNull(item);
            synchronized (this) {
                Node<T> node = find(root, item, order);
                return node == null ? 0 : node.count;
            }
        }

        public T get(int index) {
            synchronized (this) {
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("index: " + index);
                }

                Node<T> node = root;
                int remaining = index;
                while (node != null) {
                    int leftSize = subtreeSize(node.left);
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
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
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

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, RED, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = make(node.item, node.count, node.red, insert(node.left, item), node.right);
        } else if (comparison > 0) {
            node = make(node.item, node.count, node.red, node.left, insert(node.right, item));
        } else {
            node = make(node.item, node.count + 1, node.red, node.left, node.right);
        }
        return fixUp(node);
    }

    private Node<T> changeCount(Node<T> node, T item, int newCount) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return make(node.item, node.count, node.red,
                    changeCount(node.left, item, newCount), node.right);
        }
        if (comparison > 0) {
            return make(node.item, node.count, node.red,
                    node.left, changeCount(node.right, item, newCount));
        }
        return make(node.item, newCount, node.red, node.left, node.right);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = make(node.item, node.count, node.red,
                        delete(node.left, item), node.right);
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
                    Node<T> successor = minimum(node.right);
                    node = make(successor.item, successor.count, node.red,
                            node.left, deleteMin(node.right));
                } else {
                    node = make(node.item, node.count, node.red,
                            node.left, delete(node.right, item));
                }
            }
        }
        return fixUp(node);
    }

    private Node<T> deleteMin(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = make(node.item, node.count, node.red,
                deleteMin(node.left), node.right);
        return fixUp(node);
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<T> right = rotateRight(node.right);
            node = make(node.item, node.count, node.red, node.left, right);
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

    private Node<T> fixUp(Node<T> node) {
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

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> left = make(node.item, node.count, RED, node.left, right.left);
        return make(right.item, right.count, node.red, left, right.right);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> right = make(node.item, node.count, RED, left.right, node.right);
        return make(left.item, left.count, node.red, left.left, right);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> left = node.left == null ? null : withColor(node.left, !node.left.red);
        Node<T> right = node.right == null ? null : withColor(node.right, !node.right.red);
        return make(node.item, node.count, !node.red, left, right);
    }

    private static <T> Node<T> make(T item, int count, boolean red,
                                    Node<T> left, Node<T> right) {
        return new Node<T>(item, count, red, left, right);
    }

    private static <T> Node<T> withColor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return make(node.item, node.count, red, node.left, node.right);
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red == RED;
    }

    private static int subtreeSize(Node<?> node) {
        return node == null ? 0 : node.subtreeSize;
    }
}
