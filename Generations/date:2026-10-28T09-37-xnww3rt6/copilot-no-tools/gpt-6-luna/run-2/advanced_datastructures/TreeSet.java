interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

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

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            if (find(root, item) != null) {
                return false;
            }
            Node<T> updated = insert(root, item);
            if (isRed(updated)) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            if (find(root, item) == null) {
                return false;
            }

            Node<T> updated = root;
            if (!isRed(updated.left) && !isRed(updated.right)) {
                updated = recolor(updated, RED);
            }
            updated = delete(updated, item);
            if (updated != null && isRed(updated)) {
                updated = recolor(updated, BLACK);
            }
            root = updated;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
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
        if (item == null) {
            throw new NullPointerException();
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
            return new Snapshot<T>(order, root);
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
            if (item == null) {
                throw new NullPointerException();
            }
            return find(root, item, order) != null;
        }
    }

    private Node<T> find(Node<T> node, T item) {
        return find(node, item, order);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        Node<E> current = node;
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

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, null, null, RED);
        }

        int comparison = order.compare(item, node.item);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.item, insert(node.left, item), node.right, node.red);
        } else if (comparison > 0) {
            updated = new Node<T>(node.item, node.left, insert(node.right, item), node.red);
        } else {
            return node;
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

    private Node<T> delete(Node<T> node, T item) {
        Node<T> updated = node;

        if (order.compare(item, updated.item) < 0) {
            if (!isRed(updated.left) && !leftChildIsRed(updated.left)) {
                updated = moveRedLeft(updated);
            }
            updated = new Node<T>(
                updated.item,
                delete(updated.left, item),
                updated.right,
                updated.red
            );
        } else {
            if (isRed(updated.left)) {
                updated = rotateRight(updated);
            }

            if (order.compare(item, updated.item) == 0 && updated.right == null) {
                return null;
            }

            if (!isRed(updated.right) && !leftChildIsRed(updated.right)) {
                updated = moveRedRight(updated);
            }

            if (order.compare(item, updated.item) == 0) {
                Node<T> successor = minimum(updated.right);
                updated = new Node<T>(
                    successor.item,
                    updated.left,
                    deleteMinimum(updated.right),
                    updated.red
                );
            } else {
                updated = new Node<T>(
                    updated.item,
                    updated.left,
                    delete(updated.right, item),
                    updated.red
                );
            }
        }

        return balance(updated);
    }

    private Node<T> deleteMinimum(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        Node<T> updated = node;
        if (!isRed(updated.left) && !leftChildIsRed(updated.left)) {
            updated = moveRedLeft(updated);
        }
        updated = new Node<T>(
            updated.item,
            deleteMinimum(updated.left),
            updated.right,
            updated.red
        );
        return balance(updated);
    }

    private Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (leftChildIsRed(updated.right)) {
            updated = new Node<T>(
                updated.item,
                updated.left,
                rotateRight(updated.right),
                updated.red
            );
            updated = rotateLeft(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> updated = flipColors(node);
        if (leftChildIsRed(updated.left)) {
            updated = rotateRight(updated);
            updated = flipColors(updated);
        }
        return updated;
    }

    private Node<T> balance(Node<T> node) {
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

    private static <E> Node<E> rotateLeft(Node<E> node) {
        Node<E> right = node.right;
        Node<E> left = new Node<E>(node.item, node.left, right.left, RED);
        return new Node<E>(right.item, left, right.right, node.red);
    }

    private static <E> Node<E> rotateRight(Node<E> node) {
        Node<E> left = node.left;
        Node<E> right = new Node<E>(node.item, left.right, node.right, RED);
        return new Node<E>(left.item, left.left, right, node.red);
    }

    private static <E> Node<E> flipColors(Node<E> node) {
        return new Node<E>(
            node.item,
            toggleColor(node.left),
            toggleColor(node.right),
            !node.red
        );
    }

    private static <E> Node<E> toggleColor(Node<E> node) {
        if (node == null) {
            return null;
        }
        return recolor(node, !node.red);
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        return new Node<E>(node.item, node.left, node.right, red);
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static boolean leftChildIsRed(Node<?> node) {
        return node != null && isRed(node.left);
    }

    private static int sizeOf(Node<?> node) {
        return node == null ? 0 : node.count;
    }
}
