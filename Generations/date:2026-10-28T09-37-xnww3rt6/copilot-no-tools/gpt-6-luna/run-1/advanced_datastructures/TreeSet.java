@TreeSet.ThreadSafe
public class TreeSet<T> {
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
            this.count = 1 + nodeSize(left) + nodeSize(right);
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
            return nodeSize(root);
        }

        public E get(int index) {
            if (index < 0 || index >= nodeSize(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }
            return itemAt(root, index);
        }

        public boolean contains(E item) {
            requireNonNull(item);
            return find(order, root, item) != null;
        }
    }

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
        requireNonNull(item);
        synchronized (monitor) {
            if (find(order, root, item) != null) {
                return false;
            }
            root = insert(order, root, item);
            if (root.red) {
                root = recolor(root, BLACK);
            }
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(order, root, item) == null) {
                return false;
            }
            if (!isRed(root.left) && !isRed(root.right)) {
                root = recolor(root, RED);
            }
            root = delete(order, root, item);
            if (root != null && root.red) {
                root = recolor(root, BLACK);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return find(order, root, item) != null;
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
            return itemAt(root, index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison < 0) {
                    current = current.left;
                } else if (comparison > 0) {
                    result += nodeSize(current.left) + 1;
                    current = current.right;
                } else {
                    return result + nodeSize(current.left);
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
            throw new NullPointerException("item");
        }
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<E>(node.item, node.left, node.right, red);
    }

    private static <E> Node<E> find(Order<E> order, Node<E> root, E item) {
        Node<E> current = root;
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

    private static <E> E itemAt(Node<E> root, int index) {
        Node<E> current = root;
        int remaining = index;
        while (current != null) {
            int leftSize = nodeSize(current.left);
            if (remaining < leftSize) {
                current = current.left;
            } else if (remaining > leftSize) {
                remaining -= leftSize + 1;
                current = current.right;
            } else {
                return current.item;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static <E> Node<E> insert(Order<E> order, Node<E> node, E item) {
        if (node == null) {
            return new Node<E>(item, null, null, RED);
        }

        int comparison = order.compare(item, node.item);
        Node<E> updated;
        if (comparison < 0) {
            updated = new Node<E>(node.item, insert(order, node.left, item), node.right, node.red);
        } else {
            updated = new Node<E>(node.item, node.left, insert(order, node.right, item), node.red);
        }
        return fixUp(updated);
    }

    private static <E> Node<E> delete(Order<E> order, Node<E> node, E item) {
        Node<E> updated = node;

        if (order.compare(item, updated.item) < 0) {
            if (updated.left != null) {
                if (!isRed(updated.left) && !isRed(updated.left.left)) {
                    updated = moveRedLeft(updated);
                }
                updated = new Node<E>(
                    updated.item,
                    delete(order, updated.left, item),
                    updated.right,
                    updated.red
                );
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
                    Node<E> successor = minimum(updated.right);
                    updated = new Node<E>(
                        successor.item,
                        updated.left,
                        deleteMinimum(updated.right),
                        updated.red
                    );
                } else {
                    updated = new Node<E>(
                        updated.item,
                        updated.left,
                        delete(order, updated.right, item),
                        updated.red
                    );
                }
            }
        }

        return fixUp(updated);
    }

    private static <E> Node<E> deleteMinimum(Node<E> node) {
        if (node.left == null) {
            return null;
        }

        Node<E> updated = node;
        if (!isRed(updated.left) && !isRed(updated.left.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<E>(
            updated.item,
            deleteMinimum(updated.left),
            updated.right,
            updated.red
        );
        return fixUp(updated);
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
        Node<E> newLeft = new Node<E>(node.item, node.left, right.left, RED);
        return new Node<E>(right.item, newLeft, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(node.item, left.right, node.right, RED);
        return new Node<E>(left.item, left.left, newRight, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(
            node.item,
            recolor(node.left, !isRed(node.left)),
            recolor(node.right, !isRed(node.right)),
            !node.red
        );
    }

    private static <E> Node<E> moveRedLeft(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (updated.right != null && isRed(updated.right.left)) {
            Node<E> newRight = rotateRight(updated.right);
            updated = new Node<E>(updated.item, updated.left, newRight, updated.red);
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> moveRedRight(Node<E> node) {
        Node<E> updated = flipColors(node);
        if (updated.left != null && isRed(updated.left.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private static <E> Node<E> fixUp(Node<E> node) {
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
