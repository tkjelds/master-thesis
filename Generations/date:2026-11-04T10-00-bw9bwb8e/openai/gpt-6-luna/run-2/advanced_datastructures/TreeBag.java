@TreeBag.ThreadSafe
public final class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node<E> {
        final E key;
        final int count;
        final boolean red;
        final Node<E> left;
        final Node<E> right;
        final int total;
        final int distinct;

        Node(E key, int count, boolean red, Node<E> left, Node<E> right) {
            this.key = key;
            this.count = count;
            this.red = red;
            this.left = left;
            this.right = right;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }

        private static int total(Node<?> node) {
            return node == null ? 0 : node.total;
        }

        private static int distinct(Node<?> node) {
            return node == null ? 0 : node.distinct;
        }
    }

    private final Object mutex = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public void add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (mutex) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            Node<T> updated = insert(root, item);
            root = paint(updated, false);
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (mutex) {
            Node<T> existing = find(root, item);
            if (existing == null) {
                return false;
            }

            if (existing.count > 1) {
                root = changeCount(root, item, existing.count - 1);
                return true;
            }

            Node<T> updatedRoot = root;
            if (!isRed(updatedRoot.left) && !isRed(updatedRoot.right)) {
                updatedRoot = paint(updatedRoot, true);
            }
            updatedRoot = delete(updatedRoot, item);
            if (updatedRoot != null) {
                updatedRoot = paint(updatedRoot, false);
            }
            root = updatedRoot;
            return true;
        }
    }

    public int count(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (mutex) {
            Node<T> node = find(root, item);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (mutex) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (mutex) {
            return distinct(root);
        }
    }

    public T get(int index) {
        synchronized (mutex) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (mutex) {
            int result = 0;
            Node<T> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    result += total(node.left) + node.count;
                    node = node.right;
                } else {
                    return result + total(node.left);
                }
            }
            return result;
        }
    }

    public Snapshot<T> snapshot() {
        synchronized (mutex) {
            return new Snapshot<T>(order, root);
        }
    }

    private Node<T> insert(Node<T> node, T item) {
        if (node == null) {
            return new Node<T>(item, 1, true, null, null);
        }

        int comparison = order.compare(item, node.key);
        Node<T> updated;
        if (comparison < 0) {
            updated = new Node<T>(node.key, node.count, node.red,
                    insert(node.left, item), node.right);
        } else if (comparison > 0) {
            updated = new Node<T>(node.key, node.count, node.red,
                    node.left, insert(node.right, item));
        } else {
            updated = new Node<T>(node.key, node.count + 1, node.red,
                    node.left, node.right);
        }
        return fixUp(updated);
    }

    private Node<T> changeCount(Node<T> node, T item, int newCount) {
        int comparison = order.compare(item, node.key);
        if (comparison < 0) {
            return new Node<T>(node.key, node.count, node.red,
                    changeCount(node.left, item, newCount), node.right);
        }
        if (comparison > 0) {
            return new Node<T>(node.key, node.count, node.red,
                    node.left, changeCount(node.right, item, newCount));
        }
        return new Node<T>(node.key, newCount, node.red, node.left, node.right);
    }

    private Node<T> delete(Node<T> node, T item) {
        Node<T> h = node;

        if (order.compare(item, h.key) < 0) {
            if (!isRed(h.left) && !isRed(leftOf(h.left))) {
                h = moveRedLeft(h);
            }
            h = new Node<T>(h.key, h.count, h.red,
                    delete(h.left, item), h.right);
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }

            if (order.compare(item, h.key) == 0 && h.right == null) {
                return null;
            }

            if (!isRed(h.right) && !isRed(leftOf(h.right))) {
                h = moveRedRight(h);
            }

            if (order.compare(item, h.key) == 0) {
                Node<T> successor = minimum(h.right);
                h = new Node<T>(successor.key, successor.count, h.red,
                        h.left, deleteMin(h.right));
            } else {
                h = new Node<T>(h.key, h.count, h.red,
                        h.left, delete(h.right, item));
            }
        }
        return fixUp(h);
    }

    private Node<T> deleteMin(Node<T> node) {
        if (node.left == null) {
            return null;
        }

        Node<T> h = node;
        if (!isRed(h.left) && !isRed(leftOf(h.left))) {
            h = moveRedLeft(h);
        }
        h = new Node<T>(h.key, h.count, h.red,
                deleteMin(h.left), h.right);
        return fixUp(h);
    }

    private Node<T> minimum(Node<T> node) {
        Node<T> current = node;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private Node<T> moveRedLeft(Node<T> node) {
        Node<T> h = flipColors(node);
        if (isRed(leftOf(h.right))) {
            Node<T> newRight = rotateRight(h.right);
            h = new Node<T>(h.key, h.count, h.red, h.left, newRight);
            h = rotateLeft(h);
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> moveRedRight(Node<T> node) {
        Node<T> h = flipColors(node);
        if (isRed(leftOf(h.left))) {
            h = rotateRight(h);
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> fixUp(Node<T> node) {
        Node<T> h = node;
        if (isRed(h.right) && !isRed(h.left)) {
            h = rotateLeft(h);
        }
        if (isRed(h.left) && isRed(leftOf(h.left))) {
            h = rotateRight(h);
        }
        if (isRed(h.left) && isRed(h.right)) {
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> x = node.right;
        Node<T> newLeft = new Node<T>(node.key, node.count, true,
                node.left, x.left);
        return new Node<T>(x.key, x.count, node.red, newLeft, x.right);
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> x = node.left;
        Node<T> newRight = new Node<T>(node.key, node.count, true,
                x.right, node.right);
        return new Node<T>(x.key, x.count, node.red, x.left, newRight);
    }

    private Node<T> flipColors(Node<T> node) {
        Node<T> newLeft = paint(node.left, !node.left.red);
        Node<T> newRight = paint(node.right, !node.right.red);
        return new Node<T>(node.key, node.count, !node.red, newLeft, newRight);
    }

    private Node<T> paint(Node<T> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return new Node<T>(node.key, node.count, red, node.left, node.right);
    }

    private Node<T> find(Node<T> node, T item) {
        Node<T> current = node;
        while (current != null) {
            int comparison = order.compare(item, current.key);
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

    private T select(Node<T> node, int index) {
        if (index < 0 || index >= total(node)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

        Node<T> current = node;
        int position = index;
        while (current != null) {
            int leftTotal = total(current.left);
            if (position < leftTotal) {
                current = current.left;
            } else if (position < leftTotal + current.count) {
                return current.key;
            } else {
                position -= leftTotal + current.count;
                current = current.right;
            }
        }
        throw new IndexOutOfBoundsException("index: " + index);
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static Node<?> leftOf(Node<?> node) {
        return node == null ? null : node.left;
    }

    private static int total(Node<?> node) {
        return node == null ? 0 : node.total;
    }

    private static int distinct(Node<?> node) {
        return node == null ? 0 : node.distinct;
    }

    public static final class Snapshot<E> {
        private final Order<E> order;
        private final Node<E> root;

        private Snapshot(Order<E> order, Node<E> root) {
            this.order = order;
            this.root = root;
        }

        public int size() {
            return total(root);
        }

        public int distinctSize() {
            return distinct(root);
        }

        public int count(E item) {
            if (item == null) {
                throw new NullPointerException();
            }
            Node<E> node = root;
            while (node != null) {
                int comparison = order.compare(item, node.key);
                if (comparison < 0) {
                    node = node.left;
                } else if (comparison > 0) {
                    node = node.right;
                } else {
                    return node.count;
                }
            }
            return 0;
        }

        public E get(int index) {
            if (index < 0 || index >= total(root)) {
                throw new IndexOutOfBoundsException("index: " + index);
            }

            Node<E> node = root;
            int position = index;
            while (node != null) {
                int leftTotal = total(node.left);
                if (position < leftTotal) {
                    node = node.left;
                } else if (position < leftTotal + node.count) {
                    return node.key;
                } else {
                    position -= leftTotal + node.count;
                    node = node.right;
                }
            }
            throw new IndexOutOfBoundsException("index: " + index);
        }
    }
}
