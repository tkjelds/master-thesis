interface Order<T> {
    int compare(T left, T right);
}

@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}

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
            this.total = count + total(left) + total(right);
            this.distinct = 1 + distinct(left) + distinct(right);
        }
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
            requireItem(item);
            Node<E> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public E get(int index) {
            checkIndex(index, total(root));
            Node<E> node = root;
            int remaining = index;
            while (node != null) {
                int leftTotal = total(node.left);
                if (remaining < leftTotal) {
                    node = node.left;
                } else if (remaining < leftTotal + node.count) {
                    return node.item;
                } else {
                    remaining -= leftTotal + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            root = insert(root, item, order);
            if (root != null && root.red) {
                root = color(root, false);
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
                root = changeCount(root, item, existing.count - 1, order);
                return true;
            }

            if (!isRed(root.left) && !isRed(root.right)) {
                root = color(root, true);
            }
            root = delete(root, item, order);
            if (root != null && root.red) {
                root = color(root, false);
            }
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (lock) {
            Node<T> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (lock) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (lock) {
            return distinct(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            checkIndex(index, total(root));
            Node<T> node = root;
            int remaining = index;
            while (node != null) {
                int leftTotal = total(node.left);
                if (remaining < leftTotal) {
                    node = node.left;
                } else if (remaining < leftTotal + node.count) {
                    return node.item;
                } else {
                    remaining -= leftTotal + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException();
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
            return new Snapshot<T>(order, root);
        }
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<E> result;
        if (comparison < 0) {
            result = new Node<E>(node.item, node.count,
                    insert(node.left, item, order), node.right, node.red);
        } else if (comparison > 0) {
            result = new Node<E>(node.item, node.count,
                    node.left, insert(node.right, item, order), node.red);
        } else {
            result = new Node<E>(node.item, node.count + 1,
                    node.left, node.right, node.red);
        }
        return fixUp(result);
    }

    private static <E> Node<E> changeCount(
            Node<E> node, E item, int newCount, Order<E> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<E>(node.item, node.count,
                    changeCount(node.left, item, newCount, order), node.right, node.red);
        }
        if (comparison > 0) {
            return new Node<E>(node.item, node.count,
                    node.left, changeCount(node.right, item, newCount, order), node.red);
        }
        return new Node<E>(node.item, newCount, node.left, node.right, node.red);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(leftOf(node.left))) {
                node = moveRedLeft(node);
            }
            node = new Node<E>(node.item, node.count,
                    delete(node.left, item, order), node.right, node.red);
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            int comparison = order.compare(item, node.item);
            if (comparison == 0 && node.right == null) {
                return null;
            }
            if (!isRed(node.right) && !isRed(leftOf(node.right))) {
                node = moveRedRight(node);
            }
            comparison = order.compare(item, node.item);
            if (comparison == 0) {
                Node<E> successor = minimum(node.right);
                node = new Node<E>(successor.item, successor.count,
                        node.left, deleteMin(node.right), node.red);
            } else {
                node = new Node<E>(node.item, node.count,
                        node.left, delete(node.right, item, order), node.red);
            }
        }
        return fixUp(node);
    }

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(leftOf(node.left))) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.item, node.count,
                deleteMin(node.left), node.right, node.red);
        return fixUp(node);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.right))) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.item, node.count, node.left, right, node.red);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        node = flipColors(node);
        if (isRed(leftOf(node.left))) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private static <E> Node<E> fixUp(Node<E> node) {
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

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, node.count,
                node.left, right.left, true);
        return new Node<E>(right.item, right.count, left, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, node.count,
                left.right, node.right, true);
        return new Node<E>(left.item, left.count, left.left, right, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = color(node.left, !node.left.red);
        Node<E> right = color(node.right, !node.right.red);
        return new Node<E>(node.item, node.count, left, right, !node.red);
    }

    private static <E> Node<E> color(Node<E> node, boolean red) {
        return new Node<E>(node.item, node.count, node.left, node.right, red);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
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

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
        }
    }
}
