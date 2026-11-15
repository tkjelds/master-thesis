@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;

        private Snapshot(Order<E> order, Node<E> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct(root);
        }

        public int count(E item) {
            requireNonNull(item);
            Node<E> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public E get(int index) {
            if (index < 0 || index >= total(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return select(root, index);
        }
    }

    private static final class Node<E> {
        final E key;
        final int count;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int total;
        final int distinct;

        Node(E key, int count, Node<E> left, Node<E> right, boolean red) {
            this.key = key;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            root = insert(root, item);
            if (root.red) {
                root = recolor(root, false);
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
            if (found.count > 1) {
                root = decrement(root, item);
                return true;
            }

            if (!isRed(root.left) && !isRed(root.right)) {
                root = recolor(root, true);
            }
            root = delete(root, item);
            if (root != null && root.red) {
                root = recolor(root, false);
            }
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
            if (index < 0 || index >= total(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.key);
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
            return new Snapshot<T>(order, root);
        }
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, null, null, true);
        }

        int comparison = order.compare(item, node.key);
        if (comparison < 0) {
            node = new Node<T>(node.key, node.count, insert(node.left, item), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<T>(node.key, node.count, node.left, insert(node.right, item), node.red);
        } else {
            node = new Node<T>(node.key, node.count + 1, node.left, node.right, node.red);
        }
        return balance(node);
    }

    private Node<T> decrement(Node<T> node, T item) {
        int comparison = order.compare(item, node.key);
        if (comparison < 0) {
            return new Node<T>(node.key, node.count, decrement(node.left, item), node.right, node.red);
        }
        if (comparison > 0) {
            return new Node<T>(node.key, node.count, node.left, decrement(node.right, item), node.red);
        }
        return new Node<T>(node.key, node.count - 1, node.left, node.right, node.red);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.key) < 0) {
            if (!isRed(node.left) && !isRed(leftOf(node.left))) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.key, node.count, delete(node.left, item), node.right, node.red);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            if (order.compare(item, node.key) == 0 && node.right == null) {
                return null;
            }

            if (!isRed(node.right) && !isRed(leftOf(node.right))) {
                node = moveRedRight(node);
            }

            if (order.compare(item, node.key) == 0) {
                Node<T> successor = minimum(node.right);
                node = new Node<T>(successor.key, successor.count, node.left,
                        deleteMinimum(node.right), node.red);
            } else {
                node = new Node<T>(node.key, node.count, node.left,
                        delete(node.right, item), node.red);
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
        node = new Node<T>(node.key, node.count, deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<T> right = rotateRight(node.right);
            node = new Node<T>(node.key, node.count, node.left, right, node.red);
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
        if (isRed(node.right)) {
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
        Node<T> newLeft = new Node<T>(node.key, node.count, node.left, right.left, true);
        return new Node<T>(right.key, right.count, newLeft, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.key, node.count, left.right, node.right, true);
        return new Node<T>(left.key, left.count, left.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.key, node.count,
                recolor(node.left, !node.left.red),
                recolor(node.right, !node.right.red),
                !node.red);
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        return new Node<E>(node.key, node.count, node.left, node.right, red);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.key);
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

    private static <E> Node<E> minimum(Node<E> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <E> E select(Node<E> node, int index) {
        while (node != null) {
            int leftTotal = total(node.left);
            if (index < leftTotal) {
                node = node.left;
            } else if (index < leftTotal + node.count) {
                return node.key;
            } else {
                index -= leftTotal + node.count;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException("index out of range");
    }

    private static <E> Node<E> leftOf(Node<E> node) {
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

    private static <E> E requireNonNull(E item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        return item;
    }
}
