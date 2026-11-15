@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<E> {
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
            this.count = 1 + sizeOf(left) + sizeOf(right);
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
            return sizeOf(root);
        }

        public E get(int index) {
            if (index < 0 || index >= sizeOf(root)) {
                throw new IndexOutOfBoundsException();
            }

            Node<E> current = root;
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
            throw new IndexOutOfBoundsException();
        }

        public boolean contains(E item) {
            requireNonNull(item);
            return find(root, item, order) != null;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeSet(Order<T> order) {
        requireNonNull(order);
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item, order) != null) {
                return false;
            }
            Node<T> updated = insert(root, item, order);
            root = recolor(updated, BLACK);
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item, order) == null) {
                return false;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, RED);
            }
            updated = delete(updated, item, order);
            root = updated == null ? null : recolor(updated, BLACK);
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
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
                throw new IndexOutOfBoundsException();
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
            throw new IndexOutOfBoundsException();
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison <= 0) {
                    current = current.left;
                } else {
                    result += sizeOf(current.left) + 1;
                    current = current.right;
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

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red == RED;
    }

    private static <E> Node<E> node(E item, Node<E> left, Node<E> right, boolean red) {
        return new Node<E>(item, left, right, red);
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return node(node.item, node.left, node.right, red);
    }

    private static <E> Node<E> find(Node<E> root, E item, Order<E> order) {
        Node<E> current = root;
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private static <E> Node<E> insert(Node<E> root, E item, Order<E> order) {
        if (root == null) {
            return node(item, null, null, RED);
        }

        int comparison = order.compare(item, root.item);
        Node<E> updated;
        if (comparison < 0) {
            updated = node(root.item, insert(root.left, item, order), root.right, root.red);
        } else {
            updated = node(root.item, root.left, insert(root.right, item, order), root.red);
        }
        return balanceAfterInsert(updated);
    }

    private static <E> Node<E> balanceAfterInsert(Node<E> root) {
        if (isRed(root.right) && !isRed(root.left)) {
            root = rotateLeft(root);
        }
        if (isRed(root.left) && isRed(root.left.left)) {
            root = rotateRight(root);
        }
        if (isRed(root.left) && isRed(root.right)) {
            root = flipColors(root);
        }
        return root;
    }

    private static <E> Node<E> delete(Node<E> root, E item, Order<E> order) {
        Node<E> current = root;

        if (order.compare(item, current.item) < 0) {
            if (current.left != null) {
                if (!isRed(current.left) && !isRed(current.left.left)) {
                    current = moveRedLeft(current);
                }
                current = node(current.item, delete(current.left, item, order),
                               current.right, current.red);
            }
        } else {
            if (isRed(current.left)) {
                current = rotateRight(current);
            }

            if (order.compare(item, current.item) == 0 && current.right == null) {
                return null;
            }

            if (current.right != null) {
                if (!isRed(current.right) && !isRed(current.right.left)) {
                    current = moveRedRight(current);
                }

                if (order.compare(item, current.item) == 0) {
                    Node<E> successor = minimum(current.right);
                    current = node(successor.item, current.left,
                                   deleteMinimum(current.right), current.red);
                } else {
                    current = node(current.item, current.left,
                                   delete(current.right, item, order), current.red);
                }
            }
        }
        return balanceAfterDelete(current);
    }

    private static <E> Node<E> deleteMinimum(Node<E> root) {
        if (root.left == null) {
            return null;
        }

        Node<E> current = root;
        if (!isRed(current.left) && !isRed(current.left.left)) {
            current = moveRedLeft(current);
        }
        current = node(current.item, deleteMinimum(current.left), current.right, current.red);
        return balanceAfterDelete(current);
    }

    private static <E> Node<E> minimum(Node<E> root) {
        Node<E> current = root;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <E> Node<E> moveRedLeft(Node<E> root) {
        Node<E> current = flipColors(root);
        if (current.right != null && isRed(current.right.left)) {
            Node<E> right = rotateRight(current.right);
            current = node(current.item, current.left, right, current.red);
            current = rotateLeft(current);
            current = flipColors(current);
        }
        return current;
    }

    private static <E> Node<E> moveRedRight(Node<E> root) {
        Node<E> current = flipColors(root);
        if (current.left != null && isRed(current.left.left)) {
            current = rotateRight(current);
            current = flipColors(current);
        }
        return current;
    }

    private static <E> Node<E> balanceAfterDelete(Node<E> root) {
        if (isRed(root.right)) {
            root = rotateLeft(root);
        }
        if (isRed(root.left) && isRed(root.left.left)) {
            root = rotateRight(root);
        }
        if (isRed(root.left) && isRed(root.right)) {
            root = flipColors(root);
        }
        return root;
    }

    private static <E> Node<E> rotateLeft(Node<E> root) {
        Node<E> pivot = root.right;
        Node<E> left = node(root.item, root.left, pivot.left, RED);
        return node(pivot.item, left, pivot.right, root.red);
    }

    private static <E> Node<E> rotateRight(Node<E> root) {
        Node<E> pivot = root.left;
        Node<E> right = node(root.item, pivot.right, root.right, RED);
        return node(pivot.item, pivot.left, right, root.red);
    }

    private static <E> Node<E> flipColors(Node<E> root) {
        Node<E> left = root.left == null ? null : recolor(root.left, !root.left.red);
        Node<E> right = root.right == null ? null : recolor(root.right, !root.right.red);
        return node(root.item, left, right, !root.red);
    }
}
