@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E value;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E value, Node<E> left, Node<E> right, boolean red) {
            this.value = value;
            this.left = left;
            this.right = right;
            this.red = red;
            this.count = 1 + count(left) + count(right);
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
            return count(root);
        }

        public E get(int index) {
            if (index < 0 || index >= count(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<E> current = root;
            int remaining = index;
            while (current != null) {
                int leftCount = count(current.left);
                if (remaining < leftCount) {
                    current = current.left;
                } else if (remaining == leftCount) {
                    return current.value;
                } else {
                    remaining -= leftCount + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }

        public boolean contains(E item) {
            requireNonNull(item);
            Node<E> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.value);
                if (comparison == 0) {
                    return true;
                }
                current = comparison < 0 ? current.left : current.right;
            }
            return false;
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
            if (find(root, item) != null) {
                return false;
            }

            Node<T> updated = insert(root, item);
            if (updated != null && updated.red) {
                updated = recolor(updated, false);
            }
            root = updated;
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item) == null) {
                return false;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, true);
            }
            updated = delete(updated, item);
            if (updated != null && updated.red) {
                updated = recolor(updated, false);
            }
            root = updated;
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return find(root, item) != null;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            if (index < 0 || index >= count(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<T> current = root;
            int remaining = index;
            while (current != null) {
                int leftCount = count(current.left);
                if (remaining < leftCount) {
                    current = current.left;
                } else if (remaining == leftCount) {
                    return current.value;
                } else {
                    remaining -= leftCount + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }

    public int rank(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.value);
                if (comparison <= 0) {
                    current = current.left;
                } else {
                    result += count(current.left) + 1;
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

    private Node<T> find(Node<T> node, T item) {
        while (node != null) {
            int comparison = order.compare(item, node.value);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, null, null, true);
        }

        int comparison = order.compare(item, node.value);
        if (comparison < 0) {
            node = new Node<T>(node.value, insert(node.left, item), node.right, node.red);
        } else if (comparison > 0) {
            node = new Node<T>(node.value, node.left, insert(node.right, item), node.red);
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

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.value) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                node = new Node<T>(node.value, delete(node.left, item), node.right, node.red);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }

            int comparison = order.compare(item, node.value);
            if (comparison == 0 && node.right == null) {
                return null;
            }

            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }

                comparison = order.compare(item, node.value);
                if (comparison == 0) {
                    Node<T> successor = minimum(node.right);
                    node = new Node<T>(
                            successor.value,
                            node.left,
                            deleteMinimum(node.right),
                            node.red);
                } else {
                    node = new Node<T>(
                            node.value,
                            node.left,
                            delete(node.right, item),
                            node.red);
                }
            }
        }
        return balance(node);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<T>(node.value, deleteMinimum(node.left), node.right, node.red);
        return balance(node);
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        node = flipColors(node);
        if (node.right != null && isRed(node.right.left)) {
            Node<T> newRight = rotateRight(node.right);
            node = new Node<T>(node.value, node.left, newRight, node.red);
            node = rotateLeft(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> moveRedRight(Node<T> node) {
        node = flipColors(node);
        if (node.left != null && isRed(node.left.left)) {
            node = rotateRight(node);
            node = flipColors(node);
        }
        return node;
    }

    private Node<T> balance(Node<T> node) {
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

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        Node<T> newLeft = new Node<T>(node.value, node.left, right.left, true);
        return new Node<T>(right.value, newLeft, right.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        Node<T> newRight = new Node<T>(node.value, left.right, node.right, true);
        return new Node<T>(left.value, left.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<T> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return new Node<T>(node.value, left, right, !node.red);
    }

    private Node<T> recolor(Node<T> node, boolean red) {
        return new Node<T>(node.value, node.left, node.right, red);
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static <E> E requireNonNull(E value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }
}
