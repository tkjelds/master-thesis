interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (find(root, item) != null) {
                return false;
            }
            Node<T> updated = insert(root, item);
            root = withColor(updated, BLACK);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (find(root, item) == null) {
                return false;
            }

            Node<T> updatedRoot = root;
            if (!isRed(updatedRoot.left) && !isRed(updatedRoot.right)) {
                updatedRoot = withColor(updatedRoot, RED);
            }
            updatedRoot = delete(updatedRoot, item);
            root = updatedRoot == null ? null : withColor(updatedRoot, BLACK);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return find(root, item) != null;
        }
    }

    public int size() {
        synchronized (lock) {
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return node.item;
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            int rank = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    rank += sizeOf(node.left) + 1;
                    node = node.right;
                } else {
                    return rank + sizeOf(node.left);
                }
            }
            return rank;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<T>(root, order);
        }
    }

    public static final class Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;

        private Snapshot(Node<T> root, Order<T> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return sizeOf(root);
        }

        public T get(int index) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftSize = sizeOf(node.left);
                if (remaining < leftSize) {
                    node = node.left;
                } else if (remaining == leftSize) {
                    return node.item;
                } else {
                    remaining -= leftSize + 1;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }

        public boolean contains(T item) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            return find(root, item, order) != null;
        }
    }

    private static final class Node<T> {
        private final T item;
        private final Node<T> left;
        private final Node<T> right;
        private final boolean red;
        private final int size;

        private Node(T item, Node<T> left, Node<T> right, boolean red) {
            this.item = item;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + sizeOf(left) + sizeOf(right);
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
            return new Node<T>(item, null, null, RED);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.item, insert(node.left, item), node.right, node.red);
        } else {
            updated = new Node<T>(node.item, node.left, insert(node.right, item), node.red);
        }
        return balance(updated);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(leftOf(node.left))) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.item, delete(node.left, item), node.right, node.red);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(item, node.item) == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !isRed(leftOf(node.right))) {
                node = moveRedRight(node);
            }
            if (order.compare(item, node.item) == 0) {
                Node<T> successor = minimum(node.right);
                node = new Node<T>(
                    successor.item,
                    node.left,
                    deleteMinimum(node.right),
                    node.red
                );
            } else {
                node = new Node<T>(node.item, node.left, delete(node.right, item), node.red);
            }
        }
        return balance(node);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(leftOf(node.left))) {
            node = moveRedLeft(node);
        }
        node = new Node<T>(node.item, deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.right))) {
            Node<T> right = rotateRight(node.right);
            node = new Node<T>(node.item, node.left, right, node.red);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.left))) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> balance(Node<T> node) {
        if (isRed(node.right) && !isRed(node.left)) {
            node = rotateLeft(node);
        }
        if (isRed(node.left) && isRed(leftOf(node.left))) {
            node = rotateRight(node);
        }
        if (isRed(node.left) && isRed(node.right)) {
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> left = new Node<T>(node.item, node.left, right.left, RED);
        return new Node<T>(right.item, left, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> right = new Node<T>(node.item, left.right, node.right, RED);
        return new Node<T>(left.item, left.left, right, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        return new Node<T>(
            node.item,
            toggleColor(node.left),
            toggleColor(node.right),
            !node.red
        );
    }

    private Node<T> toggleColor(Node<T> node) {
        return node == null ? null : withColor(node, !node.red);
    }

    private Node<T> withColor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.left, node.right, red);
    }

    private static <T> Node<T> leftOf(Node<T> node) {
        return node == null ? null : node.left;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red == RED;
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.size;
    }
}
