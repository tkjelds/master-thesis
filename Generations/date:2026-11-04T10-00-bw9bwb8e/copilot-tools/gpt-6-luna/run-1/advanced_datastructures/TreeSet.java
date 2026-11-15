interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
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
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (find(root, item, order) != null) {
                return false;
            }
            if (sizeOf(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("set is too large");
            }
            Node<T> updated = insert(root, item, order);
            root = recolor(updated, BLACK);
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
            Node<T> start = root;
            if (!isRed(start.left) && !isRed(start.right)) {
                start = recolor(start, RED);
            }
            Node<T> updated = delete(start, item, order);
            root = updated == null ? null : recolor(updated, BLACK);
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
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            Node<T> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = sizeOf(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining == leftSize) {
                    return current.item;
                } else {
                    remaining -= leftSize + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison < 0) {
                    current = current.left;
                } else if (comparison > 0) {
                    result += sizeOf(current.left) + 1;
                    current = current.right;
                } else {
                    return result + sizeOf(current.left);
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (monitor) {
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
            Node<T> current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = sizeOf(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining == leftSize) {
                    return current.item;
                } else {
                    remaining -= leftSize + 1;
                    current = current.right;
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

    private static final boolean RED = true;
    private static final boolean BLACK = false;

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

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <T> Node<T> recolor(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.item, node.left, node.right, red);
    }

    private static <T> Node<T> find(Node<T> node, T item, Order<T> order) {
        Node<T> current = node;
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison < 0) {
                current = current.left;
            } else if (comparison > 0) {
                current = current.right;
            } else {
                return current;
            }
        }
        return null;
    }

    private static <T> Node<T> insert(Node<T> node, T item, Order<T> order) {
        if (node == null) {
            return new Node<T>(item, null, null, RED);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.item, insert(node.left, item, order),
                    node.right, node.red);
        } else {
            updated = new Node<T>(node.item, node.left,
                    insert(node.right, item, order), node.red);
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

    private static <T> Node<T> delete(Node<T> node, T item, Order<T> order) {
        Node<T> updated = node;

        if (order.compare(item, updated.item) < 0) {
            if (updated.left != null) {
                if (!isRed(updated.left) && !isRed(updated.left.left)) {
                    updated = moveRedLeft(updated);
                }
                updated = new Node<T>(updated.item,
                        delete(updated.left, item, order), updated.right, updated.red);
            }
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }
            if (order.compare(item, updated.item) == 0 && updated.right == null) {
                return null;
            }
            if (updated.right != null) {
                if (!isRed(updated.right) && !isRed(updated.right.left)) {
                    updated = moveRedRight(updated);
                }
                if (order.compare(item, updated.item) == 0) {
                    Node<T> successor = minimum(updated.right);
                    updated = new Node<T>(successor.item, updated.left,
                            deleteMinimum(updated.right), updated.red);
                } else {
                    updated = new Node<T>(updated.item, updated.left,
                            delete(updated.right, item, order), updated.red);
                }
            }
        }
        return balanceAfterDelete(updated);
    }

    private static <T> Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }
        Node<T> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<T>(updated.item, deleteMinimum(updated.left),
                updated.right, updated.red);
        return balanceAfterDelete(updated);
    }

    private static <T> Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <T> Node<T> moveRedLeft(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (isRed(updated.right.left)) {
            Node<T> newRight = rotateRight(updated.right);
            updated = new Node<T>(updated.item, updated.left, newRight, updated.red);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <T> Node<T> moveRedRight(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <T> Node<T> balanceAfterDelete(Node<T> node) {
        Node<T> updated = node;
        if (isRed(updated.right)) {
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

    private static <T> Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.item, node.left, right.left, RED);
        return new Node<T>(right.item, newLeft, right.right, node.red);
    }

    private static <T> Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.item, left.right, node.right, RED);
        return new Node<T>(left.item, left.left, newRight, node.red);
    }

    private static <T> Node<T> flipColors(Node<T> node) {
        return new Node<T>(node.item, recolor(node.left, !node.left.red),
                recolor(node.right, !node.right.red), !node.red);
    }
}
