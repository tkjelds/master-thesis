interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int count;

        Node(E item, boolean red, Node<E> left, Node<E> right) {
            this.item = item;
            this.red = red;
            this.left = left;
            this.right = right;
            this.count = 1 + count(left) + count(right);
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

    // Operations wait only to acquire the monitor. If the size cannot be
    // represented by an int, adding a new element throws IllegalStateException.
    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (find(root, item, order)) {
                return false;
            }
            if (count(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("Set is too large");
            }
            Node<T> newRoot = insert(root, item, order);
            root = withColor(newRoot, false);
            return true;
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (lock) {
            if (!find(root, item, order)) {
                return false;
            }

            Node<T> newRoot = root;
            if (!isRed(newRoot.left) && !isRed(newRoot.right)) {
                newRoot = withColor(newRoot, true);
            }
            newRoot = delete(newRoot, item, order);
            if (newRoot != null) {
                newRoot = withColor(newRoot, false);
            }
            root = newRoot;
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
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (lock) {
            return itemAt(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (lock) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.item);
                if (comparison <= 0) {
                    node = node.left;
                } else {
                    result += 1 + count(node.left);
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
            return count(root);
        }

        public E get(int index) {
            return itemAt(root, index);
        }

        public boolean contains(E item) {
            requireItem(item);
            // Share the live set's monitor so calls to its order are serialized.
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

    private static <E> int count(Node<E> node) {
        return node == null ? 0 : node.count;
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private static <E> boolean find(Node<E> node, E item, Order<E> order) {
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return true;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return false;
    }

    private static <E> E itemAt(Node<E> node, int index) {
        if (index < 0 || index >= count(node)) {
            throw new IndexOutOfBoundsException();
        }
        while (node != null) {
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
        throw new IndexOutOfBoundsException();
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

    private static <E> Node<E> insert(Node<E> node, E item, Order<E> order) {
        if (node == null) {
            return new Node<E>(item, true, null, null);
        }

        int comparison = order.compare(item, node.item);
        if (comparison < 0) {
            node = new Node<E>(node.item, node.red,
                    insert(node.left, item, order), node.right);
        } else if (comparison > 0) {
            node = new Node<E>(node.item, node.red,
                    node.left, insert(node.right, item, order));
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

    private static <E> Node<E> deleteMin(Node<E> node) {
        if (node.left == null) {
            return null;
        }
        if (!isRed(node.left) && !isRed(node.left.left)) {
            node = moveRedLeft(node);
        }
        node = new Node<E>(node.item, node.red,
                deleteMin(node.left), node.right);
        return balance(node);
    }

    private static <E> Node<E> delete(Node<E> node, E item, Order<E> order) {
        if (order.compare(item, node.item) < 0) {
            if (!isRed(node.left) && !isRed(node.left.left)) {
                node = moveRedLeft(node);
            }
            node = new Node<E>(node.item, node.red,
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
                Node<E> successor = node.right;
                while (successor.left != null) {
                    successor = successor.left;
                }
                node = new Node<E>(successor.item, node.red,
                        node.left, deleteMin(node.right));
            } else {
                node = new Node<E>(node.item, node.red,
                        node.left, delete(node.right, item, order));
            }
        }
        return balance(node);
    }
}
