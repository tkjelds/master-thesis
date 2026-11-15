interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E value;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int size;

        Node(E value, Node<E> left, Node<E> right, boolean red) {
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + nodeSize(left) + nodeSize(right);
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
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
        synchronized (monitor) {
            if (find(root, item, order) != null) {
                return false;
            }
            Node<T> updated = insert(root, item, order);
            root = recolor(updated, false);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (find(root, item, order) == null) {
                return false;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, item, order);
            root = updated == null ? null : recolor(updated, false);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            return find(root, item, order) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return nodeSize(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return getAt(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            return rankOf(root, item, order);
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
            return new Snapshot<T>(root, order);
        }
    }

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;

        private Snapshot(Node<E> root, Order<E> order) {
            this.root = root;
            this.order = order;
        }

        public int size() {
            return nodeSize(root);
        }

        public E get(int index) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return getAt(root, index);
        }

        public boolean contains(E item) {
            if (item == null) {
                throw new NullPointerException("item");
            }
            return find(root, item, order) != null;
        }
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.value, node.left, node.right, red);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.value);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private static <E> E getAt(Node<E> node, int index) {
        while (node != null) {
            int leftSize = nodeSize(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index == leftSize) {
                return node.value;
            } else {
                index -= leftSize + 1;
                node = node.right;
            }
        }
        throw new IndexOutOfBoundsException();
    }

    private static <E> int rankOf(Node<E> node, E item, Order<E> order) {
        int rank = 0;
        while (node != null) {
            int comparison = order.compare(item, node.value);
            if (comparison <= 0) {
                node = node.left;
            } else {
                rank += nodeSize(node.left) + 1;
                node = node.right;
            }
        }
        return rank;
    }

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, null, null, true);
        }

        int comparison = order.compare(item, node.value);
        Node<E> updated;
        if (comparison < 0) {
            updated = new Node<E>(node.value, insert(node.left, item, order),
                    node.right, node.red);
        } else {
            updated = new Node<E>(node.value, node.left,
                    insert(node.right, item, order), node.red);
        }
        return balance(updated);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        Node<E> updated = node;

        if (order.compare(item, updated.value) < 0) {
            if (!isRed(updated.left) && !isRed(updated.left == null ? null : updated.left.left)) {
                updated = moveRedLeft(updated);
            }
            updated = new Node<E>(updated.value,
                    delete(updated.left, item, order), updated.right, updated.red);
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }

            int comparison = order.compare(item, updated.value);
            if (comparison == 0 && updated.right == null) {
                return null;
            }

            if (!isRed(updated.right)
                    && !isRed(updated.right == null ? null : updated.right.left)) {
                updated = moveRedRight(updated);
            }

            comparison = order.compare(item, updated.value);
            if (comparison == 0) {
                Node<E> successor = minimum(updated.right);
                updated = new Node<E>(successor.value, updated.left,
                        deleteMin(updated.right), updated.red);
            } else {
                updated = new Node<E>(updated.value, updated.left,
                        delete(updated.right, item, order), updated.red);
            }
        }
        return balance(updated);
    }

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }

        Node<E> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<E>(updated.value, deleteMin(updated.left),
                updated.right, updated.red);
        return balance(updated);
    }

    private static <E> Node<E> minimum(Node<E> node) {
        Node<E> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> leftPart = new Node<E>(node.value, node.left, right.left, true);
        return new Node<E>(right.value, leftPart, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> rightPart = new Node<E>(node.value, left.right, node.right, true);
        return new Node<E>(left.value, left.left, rightPart, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = recolor(node.left, !node.left.red);
        Node<E> right = recolor(node.right, !node.right.red);
        return new Node<E>(node.value, left, right, !node.red);
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (isRed(updated.right.left)) {
            Node<E> newRight = rotateRight(updated.right);
            updated = new Node<E>(updated.value, updated.left, newRight, updated.red);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> balance(Node<E> node) {
        Node<E> updated = node;
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
}
