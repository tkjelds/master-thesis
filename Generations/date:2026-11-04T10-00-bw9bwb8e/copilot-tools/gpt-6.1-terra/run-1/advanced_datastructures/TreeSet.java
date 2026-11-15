interface Order<T> {
    int compare(T left, T right);
}

// Operations never wait for elements or capacity; monitor acquisition may block.
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E item, boolean red, Node<E> left, Node<E> right) {
            long total = 1L + count(left) + count(right);
            if (total > 2147483647L) {
                throw new IllegalStateException("Tree size exceeds integer range");
            }
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = (int) total;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> paint(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, red, node.left, node.right);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, true, node.left, right.left);
        return new Node<E>(right.item, node.red, left, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, true, left.right, node.right);
        return new Node<E>(left.item, node.red, left.left, right);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, !node.red,
                paint(node.left, !node.left.red),
                paint(node.right, !node.right.red));
    }

    private static <E> Node<E> balance(Node<E> node) {
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

    private static <E> Node<E> balanceAfterDeletion(Node<E> node) {
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

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, true, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison == 0) {
            return node;
        }
        if (comparison < 0) {
            Node<T> left = insert(node.left, item);
            if (left == node.left) {
                return node;
            }
            node = new Node<T>(node.item, node.red, left, node.right);
        } else {
            Node<T> right = insert(node.right, item);
            if (right == node.right) {
                return node;
            }
            node = new Node<T>(node.item, node.red, node.left, right);
        }
        return balance(node);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            Node<E> right = rotateRight(node.right);
            node = new Node<E>(node.item, node.red, node.left, right);
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

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.item, node.red, deleteMin(node.left), node.right);
        return balanceAfterDeletion(node);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.item, node.red, delete(node.left, item), node.right);
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
                Node<T> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                node = new Node<T>(successor.item, node.red, node.left,
                        deleteMin(node.right));
            } else {
                node = new Node<T>(node.item, node.red, node.left,
                        delete(node.right, item));
            }
        }
        return balanceAfterDeletion(node);
    }

    static <E> boolean containsNode(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    static <E> E getNode(Node<E> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException();
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
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node<T> updated = insert(root, item);
            if (updated == root) {
                return false;
            }
            root = paint(updated, false);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            if (!containsNode(root, item, order)) {
                return false;
            }
            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = paint(updated, true);
            }
            updated = delete(updated, item);
            root = paint(updated, false);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            return containsNode(root, item, order);
        }
    }

    public int size() {
        synchronized (lock) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return getNode(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += count(node.left) + 1;
                    node = node.right;
                }
            }
            return result;
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
        return TreeSet.getNode(root, index);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            return TreeSet.containsNode(root, item, order);
        }
    }
}
