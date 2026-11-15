interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

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
            this.size = 1 + nodeSize(left) + nodeSize(right);
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

    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (find(root, item, order)) {
                return false;
            }
            if (nodeSize(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("Set is too large");
            }
            Node<T> next = insert(root, item);
            root = withColor(next, false);
            return true;
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
            if (!find(root, item, order)) {
                return false;
            }
            Node<T> next = root;
            if (!isRed(next.left) && !isRed(next.right)) {
                next = withColor(next, true);
            }
            next = delete(next, item);
            if (next != null) {
                next = withColor(next, false);
            }
            root = next;
            return true;
        }
    }

    public boolean contains(T item) {
        requireItem(item);
        synchronized (lock) {
            return find(root, item, order);
        }
    }

    public int size() {
        synchronized (lock) {
            return nodeSize(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (lock) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison < 0) {
                    current = current.left;
                } else if (comparison > 0) {
                    result += 1 + nodeSize(current.left);
                    current = current.right;
                } else {
                    return result + nodeSize(current.left);
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

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;
        private final Object lock;

        private Snapshot(Node<E> root, Order<E> order, Object lock) {
            this.root = root;
            this.order = order;
            this.lock = lock;
        }

        public int size() {
            return nodeSize(root);
        }

        public E get(int index) {
            return select(root, index);
        }

        public boolean contains(E item) {
            requireItem(item);
            synchronized (lock) {
                return find(root, item, order);
            }
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException();
        }
    }

    private static int nodeSize(Node<?> node) {
        return node == null ? 0 : node.size;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
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

    private static <E> E select(Node<E> node, int index) {
        if (index < 0 || index >= nodeSize(node)) {
            throw new IndexOutOfBoundsException();
        }
        while (true) {
            int leftSize = nodeSize(node.left);
            if (index < leftSize) {
                node = node.left;
            } else if (index == leftSize) {
                return node.item;
            } else {
                index -= leftSize + 1;
                node = node.right;
            }
        }
    }

    private static <E> Node<E> withColor(Node<E> node, boolean red) {
        if (node.red == red) {
            return node;
        }
        return new Node<E>(node.item, red, node.left, node.right);
    }

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> newLeft = new Node<E>(
                node.item, true, node.left, right.left);
        return new Node<E>(right.item, node.red, newLeft, right.right);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> newRight = new Node<E>(
                node.item, true, left.right, node.right);
        return new Node<E>(left.item, node.red, left.left, newRight);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        Node<E> left = withColor(node.left, !node.left.red);
        Node<E> right = withColor(node.right, !node.right.red);
        return new Node<E>(node.item, !node.red, left, right);
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, true, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<T>(
                    node.item, node.red, insert(node.left, item), node.right);
        } else if (comparison > 0) {
            node = new Node<T>(
                    node.item, node.red, node.left, insert(node.right, item));
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
        Node<E> left = deleteMinimum(node.left);
        node = new Node<E>(node.item, node.red, left, node.right);
        return balance(node);
    }

    private Node<T> delete(Node<T> node, T item) {
        if (order.compare(item, node.item) < 0) {
            if (node.left != null) {
                if (!isRed(node.left) && !isRed(node.left.left)) {
                    node = moveRedLeft(node);
                }
                Node<T> left = delete(node.left, item);
                node = new Node<T>(node.item, node.red, left, node.right);
            }
        } else {
            if (isRed(node.left)) {
                node = rotateRight(node);
            }
            if (order.compare(item, node.item) == 0 && node.right == null) {
                return null;
            }
            if (node.right != null) {
                if (!isRed(node.right) && !isRed(node.right.left)) {
                    node = moveRedRight(node);
                }
                if (order.compare(item, node.item) == 0) {
                    Node<T> successor = minimum(node.right);
                    Node<T> right = deleteMinimum(node.right);
                    node = new Node<T>(
                            successor.item, node.red, node.left, right);
                } else {
                    Node<T> right = delete(node.right, item);
                    node = new Node<T>(
                            node.item, node.red, node.left, right);
                }
            }
        }
        return balance(node);
    }
}
