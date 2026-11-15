@TreeSet.ThreadSafe
public final class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E item;
        final Node<E> left;
        final Node<E> right;
        final boolean red;
        final int size;

        Node(E item, Node<E> left, Node<E> right, boolean red) {
            this.item = item;
            this.left = left;
            this.right = right;
            this.red = red;
            this.size = 1 + sizeOf(left) + sizeOf(right);
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
            checkIndex(index, sizeOf(root));
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
            Node<E> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
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
        this.order = requireNonNull(order);
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(root, item) != null) {
                return false;
            }
            Node<T> updated = insert(root, item);
            root = recolor(updated, false);
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
            root = updated == null ? null : recolor(updated, false);
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
            return sizeOf(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            checkIndex(index, sizeOf(root));
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

    private Node<T> find(Node<T> current, T item) {
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private Node<T> insert(Node<T> current, T item) {
        if (current == null) {
            return new Node<T>(item, null, null, true);
        }

        int comparison = order.compare(item, current.item);
        if (comparison < 0) {
            current = new Node<T>(current.item, insert(current.left, item), current.right, current.red);
        } else {
            current = new Node<T>(current.item, current.left, insert(current.right, item), current.red);
        }
        return fixUp(current);
    }

    private Node<T> delete(Node<T> current, T item) {
        if (order.compare(item, current.item) < 0) {
            if (!isRed(current.left) && !isRed(leftOf(current.left))) {
                current = moveRedLeft(current);
            }
            current = new Node<T>(current.item, delete(current.left, item), current.right, current.red);
        } else {
            if (isRed(current.left)) {
                current = rotateRight(current);
            }
            int comparison = order.compare(item, current.item);
            if (comparison == 0 && current.right == null) {
                return null;
            }
            if (!isRed(current.right) && !isRed(leftOf(current.right))) {
                current = moveRedRight(current);
            }
            comparison = order.compare(item, current.item);
            if (comparison == 0) {
                Node<T> minimum = minimum(current.right);
                current = new Node<T>(minimum.item, current.left, deleteMin(current.right), current.red);
            } else {
                current = new Node<T>(current.item, current.left, delete(current.right, item), current.red);
            }
        }
        return fixUp(current);
    }

    private Node<T> deleteMin(Node<T> current) {
        if (current.left == null) {
            return null;
        }
        if (!isRed(current.left) && !isRed(leftOf(current.left))) {
            current = moveRedLeft(current);
        }
        current = new Node<T>(current.item, deleteMin(current.left), current.right, current.red);
        return fixUp(current);
    }

    private Node<T> minimum(Node<T> current) {
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> moveRedLeft(Node<T> current) {
        current = flipColors(current);
        if (isRed(leftOf(current.right))) {
            Node<T> right = rotateRight(current.right);
            current = new Node<T>(current.item, current.left, right, current.red);
            current = rotateLeft(current);
            current = flipColors(current);
        }
        return current;
    }

    private Node<T> moveRedRight(Node<T> current) {
        current = flipColors(current);
        if (isRed(leftOf(current.left))) {
            current = rotateRight(current);
            current = flipColors(current);
        }
        return current;
    }

    private Node<T> fixUp(Node<T> current) {
        if (isRed(current.right)) {
            current = rotateLeft(current);
        }
        if (isRed(current.left) && isRed(leftOf(current.left))) {
            current = rotateRight(current);
        }
        if (isRed(current.left) && isRed(current.right)) {
            current = flipColors(current);
        }
        return current;
    }

    private Node<T> rotateLeft(Node<T> current) {
        Node<T> right = current.right;
        Node<T> left = new Node<T>(current.item, current.left, right.left, true);
        return new Node<T>(right.item, left, right.right, current.red);
    }

    private Node<T> rotateRight(Node<T> current) {
        Node<T> left = current.left;
        Node<T> right = new Node<T>(current.item, left.right, current.right, true);
        return new Node<T>(left.item, left.left, right, current.red);
    }

    private Node<T> flipColors(Node<T> current) {
        return new Node<T>(
            current.item,
            recolor(current.left, !current.left.red),
            recolor(current.right, !current.right.red),
            !current.red
        );
    }

    private static <E> Node<E> recolor(Node<E> current, boolean red) {
        if (current == null || current.red == red) {
            return current;
        }
        return new Node<E>(current.item, current.left, current.right, red);
    }

    private static <E> Node<E> leftOf(Node<E> current) {
        return current == null ? null : current.left;
    }

    private static boolean isRed(Node<?> current) {
        return current != null && current.red;
    }

    private static int sizeOf(Node<?> current) {
        return current == null ? 0 : current.size;
    }

    private static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private static <E> E requireNonNull(E value) {
        if (value == null) {
            throw new NullPointerException();
        }
        return value;
    }
}
