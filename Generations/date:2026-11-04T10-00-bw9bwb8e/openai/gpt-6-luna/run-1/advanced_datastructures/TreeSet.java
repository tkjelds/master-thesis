interface Order<T> {
    int compare(T left, T right);
}

@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Object mutex = new Object();
    private Node<T> root;

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
            this.count = 1 + count(left) + count(right);
        }
    }

    public TreeSet(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            if (find(root, item) != null) {
                return false;
            }
            root = insert(root, item);
            if (root.red) {
                root = recolor(root, false);
            }
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            if (find(root, item) == null) {
                return false;
            }
            if (!isRed(root.left) && !isRed(root.right)) {
                root = recolor(root, true);
            }
            root = delete(root, item);
            if (root != null && root.red) {
                root = recolor(root, false);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (mutex) {
            return find(root, item) != null;
        }
    }

    public int size() {
        synchronized (mutex) {
            return count(root);
        }
    }

    public T get(int index) {
        synchronized (mutex) {
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
                    return current.item;
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
        synchronized (mutex) {
            int result = 0;
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
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
        synchronized (mutex) {
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
                    return current.item;
                } else {
                    remaining -= leftCount + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }

        public boolean contains(E item) {
            requireNonNull(item);
            return find(root, item, order) != null;
        }
    }

    private static void requireNonNull(Object value) {
        if (value == null) {
            throw new NullPointerException("item");
        }
    }

    private static <E> int count(Node<E> node) {
        return node == null ? 0 : node.count;
    }

    private static <E> boolean isRed(Node<E> node) {
        return node != null && node.red;
    }

    private Node<T> find(Node<T> node, T item) {
        return find(node, item, order);
    }

    private static <E> Node<E> find(Node<E> node, E item, Order<E> order) {
        Node<E> current = node;
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private Node<T> makeNode(T item, Node<T> left, Node<T> right, boolean red) {
        return new Node<T>(item, left, right, red);
    }

    private Node<T> recolor(Node<T> node, boolean red) {
        return makeNode(node.item, node.left, node.right, red);
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return makeNode(item, null, null, true);
        }

        int comparison = order.compare(item, node.item);
        Node<T> result;
        if (comparison < 0) {
            result = makeNode(node.item, insert(node.left, item), node.right, node.red);
        } else {
            result = makeNode(node.item, node.left, insert(node.right, item), node.red);
        }

        if (isRed(result.right) && !isRed(result.left)) {
            result = rotateLeft(result);
        }
        if (isRed(result.left) && isRed(result.left.left)) {
            result = rotateRight(result);
        }
        if (isRed(result.left) && isRed(result.right)) {
            result = flipColors(result);
        }
        return result;
    }

    private Node<T> delete(Node<T> node, T item) {
        Node<T> result = node;

        if (order.compare(item, result.item) < 0) {
            if (!isRed(result.left) && !isRed(result.left == null ? null : result.left.left)) {
                result = moveRedLeft(result);
            }
            result = makeNode(result.item, delete(result.left, item), result.right, result.red);
        } else {
            if (isRed(result.left)) {
                result = rotateRight(result);
            }

            int comparison = order.compare(item, result.item);
            if (comparison == 0 && result.right == null) {
                return null;
            }

            if (!isRed(result.right) &&
                !isRed(result.right == null ? null : result.right.left)) {
                result = moveRedRight(result);
            }

            comparison = order.compare(item, result.item);
            if (comparison == 0) {
                Node<T> minimum = minimum(result.right);
                result = makeNode(minimum.item, result.left, deleteMin(result.right), result.red);
            } else {
                result = makeNode(result.item, result.left, delete(result.right, item), result.red);
            }
        }
        return balance(result);
    }

    private Node<T> deleteMin(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        Node<T> result = node;
        if (!isRed(result.left) &&
            !isRed(result.left == null ? null : result.left.left)) {
            result = moveRedLeft(result);
        }
        result = makeNode(result.item, deleteMin(result.left), result.right, result.red);
        return balance(result);
    }

    private Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
        Node<T> newLeft = makeNode(node.item, node.left, pivot.left, true);
        return makeNode(pivot.item, newLeft, pivot.right, node.red);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> pivot = node.left;
        Node<T> newRight = makeNode(node.item, pivot.right, node.right, true);
        return makeNode(pivot.item, pivot.left, newRight, node.red);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> left = node.left == null ? null : recolor(node.left, !node.left.red);
        Node<T> right = node.right == null ? null : recolor(node.right, !node.right.red);
        return makeNode(node.item, left, right, !node.red);
    }

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> result = flipColors(node);
        if (result.right != null && isRed(result.right.left)) {
            Node<T> newRight = rotateRight(result.right);
            result = makeNode(result.item, result.left, newRight, result.red);
            result = rotateLeft(result);
            result = flipColors(result);
        }
        return result;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> result = flipColors(node);
        if (result.left != null && isRed(result.left.left)) {
            result = rotateRight(result);
            result = flipColors(result);
        }
        return result;
    }

    private Node<T> balance(Node<T> node) {
        Node<T> result = node;
        if (isRed(result.right)) {
            result = rotateLeft(result);
        }
        if (isRed(result.left) && isRed(result.left.left)) {
            result = rotateRight(result);
        }
        if (isRed(result.left) && isRed(result.right)) {
            result = flipColors(result);
        }
        return result;
    }
}
