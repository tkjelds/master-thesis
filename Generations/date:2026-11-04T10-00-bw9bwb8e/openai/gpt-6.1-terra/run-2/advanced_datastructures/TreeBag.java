interface Order<T> {
    int compare(T left, T right);
}

@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    static final class Node<E> {
        final E item;
        final int count;
        final int total;
        final boolean red;
        final Node<E> left;
        final Node<E> right;

        Node(E item, int count, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.total = total(left) + count + total(right);
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;
    private int distinct;

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
                throw new IllegalStateException("size overflow");
            }

            Node<T> existing = find(root, item, order);
            Node<T> updated;
            if (existing != null) {
                updated = changeCount(root, item, existing.count + 1, order);
            } else {
                updated = insert(root, item, order);
                updated = paint(updated, BLACK);
            }
            root = updated;
            if (existing == null) {
                distinct++;
            }
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> existing = find(root, item, order);
            if (existing == null) {
                return false;
            }

            if (existing.count > 1) {
                root = changeCount(root, item, existing.count - 1, order);
            } else {
                Node<T> updated = root;
                if (!isRed(updated.left) && !isRed(updated.right)) {
                    updated = paint(updated, RED);
                }
                updated = delete(updated, item, order);
                if (updated != null) {
                    updated = paint(updated, BLACK);
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
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += total(node.left) + node.count;
                    node = node.right;
                } else {
                    return result + total(node.left);
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

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
    }

    static <E> int total(Node<E> node) {
        return node == null ? 0 : node.total;
    }

    static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
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

    static <E> E select(Node<E> node, int index) {
        if (index < 0 || index >= total(node)) {
            throw new IndexOutOfBoundsException();
        }
        while (node != null) {
            int leftTotal = total(node.left);
            if (index < leftTotal) {
                node = node.left;
            } else if (index - leftTotal < node.count) {
                return node.item;
            } else {
                index -= leftTotal + node.count;
                node = node.right;
            }
        }
        throw new IllegalStateException("invalid tree");
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> paint(Node<E> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.count, red, node.left, node.right);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> newLeft = new Node<E>(
                node.item, node.count, RED, node.left, right.left);
        return new Node<E>(
                right.item, right.count, node.red, newLeft, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(
                node.item, node.count, RED, left.right, node.right);
        return new Node<E>(
                left.item, left.count, node.red, left.left, newRight);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(
                node.item, node.count, !node.red,
                paint(node.left, !node.left.red),
                paint(node.right, !node.right.red));
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, 1, RED, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<E>(
                    node.item, node.count, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            node = new Node<E>(
                    node.item, node.count, node.red,
                    node.left, insert(node.right, item, order));
        } else {
            node = new Node<E>(
                    node.item, node.count + 1, node.red, node.left, node.right);
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

    private static <E> Node<E> changeCount(
            Node<E> node, E item, int newCount, Order<E> order) {
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            return new Node<E>(
                    node.item, node.count, node.red,
                    changeCount(node.left, item, newCount, order), node.right);
        }
        if (comparison > 0) {
            return new Node<E>(
                    node.item, node.count, node.red,
                    node.left, changeCount(node.right, item, newCount, order));
        }
        return new Node<E>(
                node.item, newCount, node.red, node.left, node.right);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(
                    node.item, node.count, node.red, node.left, right);
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
        node = new Node<E>(
                node.item, node.count, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<E>(
                    node.item, node.count, node.red,
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
                Node<E> newRight = deleteMinimum(node.right);
                node = new Node<E>(
                        successor.item, successor.count, node.red,
                        node.left, newRight);
            } else {
                node = new Node<E>(
                        node.item, node.count, node.red,
                        node.left, delete(node.right, item, order));
            }
        }
        return balance(node);
    }
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeBag.Node<T> root;
    private final int distinct;
    private final Order<T> order;
    private final Object monitor;

    Snapshot(TreeBag.Node<T> root, int distinct, Order<T> order, Object monitor) {
        this.root = root;
        this.distinct = distinct;
        this.order = order;
        this.monitor = monitor;
    }

    public int size() {
        return TreeBag.total(root);
    }

    public int distinctSize() {
        return distinct;
    }

    public int count(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            TreeBag.Node<T> node = TreeBag.find(root, item, order);
            return node == null ? 0 : node.count;
        }
    }

    public T get(int index) {
        return TreeBag.select(root, index);
    }
}
