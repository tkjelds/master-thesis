interface Order<T> {
    int compare(T left, T right);
}

/**
 * An indexed, persistent-node red-black set. Operations acquire a monitor but
 * never wait for elements or capacity: an absent removal returns false, and
 * an invalid index throws. Elements must retain their ordering while retained,
 * including by snapshots. Snapshots share immutable nodes and element references;
 * capturing one takes O(1), and updates copy only O(log n) nodes.
 */
@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (find(root, item, order)) {
                return false;
            }
            if (count(root) == 2147483647) {
                throw new IllegalStateException("Maximum set size reached");
            }
            Node<T> next = insert(root, item);
            root = color(next, false);
            return true;
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (!find(root, item, order)) {
                return false;
            }
            Node<T> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = color(next, true);
            }
            next = delete(next, item);
            root = color(next, false);
            return true;
        }
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (monitor) {
            return find(root, item, order);
        }
    }

    public int size() {
        synchronized (monitor) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return elementAt(root, index);
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
                    result += count(node.left) + 1;
                    node = node.right;
                } else {
                    return result + count(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order, monitor);
        }
    }

    public static final class Snapshot<T> {
        private final Node<T> root;
        private final Order<T> order;
        private final Object monitor;

        private Snapshot(Node<T> root, Order<T> order, Object monitor) {
            this.root = root;
            this.order = order;
            this.monitor = monitor;
        }

        public int size() {
            return count(root);
        }

        public T get(int index) {
            return elementAt(root, index);
        }

        public boolean contains(T item) {
            requireItem(item);
            // Serialize calls to the supplied order, including across snapshots.
            synchronized (monitor) {
                return find(root, item, order);
            }
        }
    }

    private static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int size;

        Node(E item, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.size = 1 + count(left) + count(right);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static <E> int count(Node<E> node) {
        return node == null ? 0 : node.size;
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> color(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, red, node.left, node.right);
    }

    private static <E> boolean find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison < 0) {
                node = node.left;
            } else if (comparison > 0) {
                node = node.right;
            } else {
                return true;
            }
        }
        return false;
    }

    private static <E> E elementAt(Node<E> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }
        while (node != null) {
            int leftSize = count(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index > leftSize) {
                index -= leftSize + 1;
                node = node.right;
            } else {
                return node.item;
            }
        }
        throw new IllegalStateException("Invalid subtree counts");
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, true, null, null);
        }
        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(node.item, node.red,
                    insert(node.left, item), node.right);
        } else if (comparison > 0) {
            node = new Node<T>(node.item, node.red,
                    node.left, insert(node.right, item));
        } else {
            return node;
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

    // The preliminary membership check guarantees the deletion path exists.
    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<T>(node.item, node.red,
                    delete(node.left, item), node.right);
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
                node = new Node<T>(successor.item, node.red,
                        node.left, deleteMinimum(node.right));
            } else {
                node = new Node<T>(node.item, node.red,
                        node.left, delete(node.right, item));
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
        node = new Node<E>(node.item, node.red,
                deleteMinimum(node.left), node.right);
        return balance(node);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> pivot = node.right;
        Node<E> left = new Node<E>(node.item, true, node.left, pivot.left);
        return new Node<E>(pivot.item, node.red, left, pivot.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> pivot = node.left;
        Node<E> right = new Node<E>(node.item, true, pivot.right, node.right);
        return new Node<E>(pivot.item, node.red, pivot.left, right);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(node.item, !node.red,
                color(node.left, !node.left.red),
                color(node.right, !node.right.red));
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        node = flipColors(node);
        if (isRed(node.right.left)) {
            node = new Node<E>(node.item, node.red,
                    node.left, rotateRight(node.right));
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
}
