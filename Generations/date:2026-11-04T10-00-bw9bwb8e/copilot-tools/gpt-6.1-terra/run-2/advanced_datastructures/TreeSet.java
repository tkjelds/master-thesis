interface Order<T> {
    int compare(T left, T right);
}

/*
 * Operations wait only to acquire the collection monitor. Removing an absent
 * element returns false; accessing an invalid index throws. An insertion that
 * would exceed Integer.MAX_VALUE elements throws IllegalStateException.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E item, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.left = left;
            this.right = right;
            this.red = red;
            this.count = 1 + count(left) + count(right);
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> color(Node<E> node, boolean red) {
        return new Node<E>(node.item, node.left, node.right, red);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, node.left, right.left, true);
        return new Node<E>(right.item, left, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, left.right, node.right, true);
        return new Node<E>(left.item, left.left, right, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, color(node.left, !node.left.red),
                color(node.right, !node.right.red), !node.red);
    }

    private static <E> Node<E> insertBalance(Node<E> node) {
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

    private static <E> Node<E> deleteBalance(Node<E> node) {
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

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.item, node.left, right, node.red);
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

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, null, null, true);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(node.item, insert(node.left, item), node.right, node.red);
        } else {
            node = new Node<T>(node.item, node.left, insert(node.right, item), node.red);
        }
        return insertBalance(node);
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
        node = new Node<E>(node.item, deleteMinimum(node.left), node.right, node.red);
        return deleteBalance(node);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
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
            if (!isRed(node.right) && !isRed(node.right.left)) {
                node = moveRedRight(node);
            }
            if (order.compare(item, node.item) == 0) {
                Node<T> successor = minimum(node.right);
                node = new Node<T>(successor.item, node.left,
                        deleteMinimum(node.right), node.red);
            } else {
                node = new Node<T>(node.item, node.left, delete(node.right, item), node.red);
            }
        }
        return deleteBalance(node);
    }

    static <E> boolean contains(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    static <E> E get(Node<E> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (true) {
            int leftCount = count(node.left);
            if (index < leftCount) {
                node = node.left;
            } else if (index == leftCount) {
                return node.item;
            } else {
                index -= leftCount + 1;
                node = node.right;
            }
        }
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (contains(root, item, order)) {
                return false;
            }
            if (count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("set is too large");
            }
            Node<T> next = insert(root, item);
            root = color(next, false);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (!contains(root, item, order)) {
                return false;
            }
            Node<T> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = color(next, true);
            }
            next = delete(next, item);
            root = next == null ? null : color(next, false);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return contains(root, item, order);
        }
    }

    public int size() {
        synchronized (lock) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return get(root, index);
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
                if (comparison <= 0) {
                    if (comparison == 0) {
                        return rank + count(node.left);
                    }
                    node = node.left;
                } else {
                    rank += count(node.left) + 1;
                    node = node.right;
                }
            }
            return rank;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (lock) {
            return new Snapshot<T>(root, order, lock);
        }
    }
}

@Snapshot.ThreadSafe
final class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeSet.Node<T> root;
    private final Order<T> order;
    private final Object lock;

    Snapshot(TreeSet.Node<T> root, Order<T> order, Object lock) {
        this.root = root;
        this.order = order;
        this.lock = lock;
    }

    public int size() {
        return TreeSet.count(root);
    }

    public T get(int index) {
        return TreeSet.get(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return TreeSet.contains(root, item, order);
        }
    }
}
