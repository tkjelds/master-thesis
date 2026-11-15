@TreeBag.ThreadSafe
public class TreeBag<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node<T> {
        final T key;
        final int count;
        final Node<T> left;
        final Node<T> right;
        final boolean red;
        final int total;
        final int distinct;

        Node(T key, int count, Node<T> left, Node<T> right, boolean red) {
            this.key = key;
            this.count = count;
            this.left = left;
            this.right = right;
            this.red = red;
            this.total = total(left) + count + total(right);
            this.distinct = distinct(left) + 1 + distinct(right);
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;

    public TreeBag(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public void add(T item) {
        requireItem(item);
        synchronized (monitor) {
            if (total(root) == Integer.MAX_VALUE) {
                throw new IllegalStateException("TreeBag size would overflow");
            }
            root = put(root, item);
            if (root != null && root.red) {
                root = recolor(root, false);
            }
        }
    }

    public boolean remove(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> found = find(root, item);
            if (found == null) {
                return false;
            }

            if (found.count > 1) {
                root = decrement(root, item);
                return true;
            }

            Node<T> newRoot = root;
            if (!isRed(newRoot.left) && !isRed(newRoot.right)) {
                newRoot = recolor(newRoot, true);
            }
            newRoot = delete(newRoot, item);
            if (newRoot != null && newRoot.red) {
                newRoot = recolor(newRoot, false);
            }
            root = newRoot;
            return true;
        }
    }

    public int count(T item) {
        requireItem(item);
        synchronized (monitor) {
            Node<T> node = find(root, item);
            return node == null ? 0 : node.count;
        }
    }

    public int size() {
        synchronized (monitor) {
            return total(root);
        }
    }

    public int distinctSize() {
        synchronized (monitor) {
            return distinct(root);
        }
    }

    public T get(int index) {
        synchronized (monitor) {
            return select(root, index);
        }
    }

    public int rank(T item) {
        requireItem(item);
        synchronized (monitor) {
            return rankOf(root, item, order);
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
            return total(root);
        }

        public int distinctSize() {
            return distinct(root);
        }

        public int count(T item) {
            requireItem(item);
            Node<T> node = find(root, item, order);
            return node == null ? 0 : node.count;
        }

        public T get(int index) {
            return select(root, index);
        }
    }

    private static void requireItem(Object item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private static <T> int total(Node<T> node) {
        return node == null ? 0 : node.total;
    }

    private static <T> int distinct(Node<T> node) {
        return node == null ? 0 : node.distinct;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <T> Node<T> node(
            T key, int count, Node<T> left, Node<T> right, boolean red) {
        return new Node<T>(key, count, left, right, red);
    }

    private static <T> Node<T> recolor(Node<T> h, boolean red) {
        return node(h.key, h.count, h.left, h.right, red);
    }

    private static <T> Node<T> toggleColor(Node<T> h) {
        return h == null ? null : recolor(h, !h.red);
    }

    private Node<T> put(Node<T> h, T item) {
        if (h == null) {
            return node(item, 1, null, null, true);
        }

        int comparison = order.compare(item, h.key);
        if (comparison < 0) {
            h = node(h.key, h.count, put(h.left, item), h.right, h.red);
        } else if (comparison > 0) {
            h = node(h.key, h.count, h.left, put(h.right, item), h.red);
        } else {
            return node(h.key, h.count + 1, h.left, h.right, h.red);
        }
        return fixUp(h);
    }

    private Node<T> decrement(Node<T> h, T item) {
        int comparison = order.compare(item, h.key);
        if (comparison < 0) {
            return node(h.key, h.count, decrement(h.left, item), h.right, h.red);
        }
        if (comparison > 0) {
            return node(h.key, h.count, h.left, decrement(h.right, item), h.red);
        }
        return node(h.key, h.count - 1, h.left, h.right, h.red);
    }

    private Node<T> delete(Node<T> h, T item) {
        if (order.compare(item, h.key) < 0) {
            if (h.left != null) {
                if (!isRed(h.left) && !isRed(h.left.left)) {
                    h = moveRedLeft(h);
                }
                h = node(h.key, h.count, delete(h.left, item), h.right, h.red);
            }
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }

            int comparison = order.compare(item, h.key);
            if (comparison == 0 && h.right == null) {
                return null;
            }

            if (h.right != null) {
                if (!isRed(h.right) && !isRed(h.right.left)) {
                    h = moveRedRight(h);
                }

                comparison = order.compare(item, h.key);
                if (comparison == 0) {
                    Node<T> successor = minimum(h.right);
                    h = node(successor.key, successor.count, h.left,
                             deleteMinimum(h.right), h.red);
                } else {
                    h = node(h.key, h.count, h.left,
                             delete(h.right, item), h.red);
                }
            }
        }
        return fixUp(h);
    }

    private Node<T> deleteMinimum(Node<T> h) {
        if (h.left == null) {
            return null;
        }
        if (!isRed(h.left) && !isRed(h.left.left)) {
            h = moveRedLeft(h);
        }
        h = node(h.key, h.count, deleteMinimum(h.left), h.right, h.red);
        return fixUp(h);
    }

    private Node<T> minimum(Node<T> h) {
        while (h.left != null) {
            h = h.left;
        }
        return h;
    }

    private Node<T> moveRedLeft(Node<T> h) {
        h = flipColors(h);
        if (h.right != null && isRed(h.right.left)) {
            Node<T> newRight = rotateRight(h.right);
            h = node(h.key, h.count, h.left, newRight, h.red);
            h = rotateLeft(h);
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> moveRedRight(Node<T> h) {
        h = flipColors(h);
        if (h.left != null && isRed(h.left.left)) {
            h = rotateRight(h);
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> flipColors(Node<T> h) {
        return node(h.key, h.count, toggleColor(h.left), toggleColor(h.right), !h.red);
    }

    private Node<T> rotateLeft(Node<T> h) {
        Node<T> x = h.right;
        Node<T> newLeft = node(h.key, h.count, h.left, x.left, true);
        return node(x.key, x.count, newLeft, x.right, h.red);
    }

    private Node<T> rotateRight(Node<T> h) {
        Node<T> x = h.left;
        Node<T> newRight = node(h.key, h.count, x.right, h.right, true);
        return node(x.key, x.count, x.left, newRight, h.red);
    }

    private Node<T> fixUp(Node<T> h) {
        if (isRed(h.right) && !isRed(h.left)) {
            h = rotateLeft(h);
        }
        if (isRed(h.left) && isRed(h.left.left)) {
            h = rotateRight(h);
        }
        if (isRed(h.left) && isRed(h.right)) {
            h = flipColors(h);
        }
        return h;
    }

    private Node<T> find(Node<T> h, T item) {
        return find(h, item, order);
    }

    private static <T> Node<T> find(Node<T> h, T item, Order<T> order) {
        while (h != null) {
            int comparison = order.compare(item, h.key);
            if (comparison < 0) {
                h = h.left;
            } else if (comparison > 0) {
                h = h.right;
            } else {
                return h;
            }
        }
        return null;
    }

    private static <T> T select(Node<T> h, int index) {
        if (index < 0 || index >= total(h)) {
            throw new IndexOutOfBoundsException("index: " + index);
        }

        while (h != null) {
            int leftTotal = total(h.left);
            if (index < leftTotal) {
                h = h.left;
            } else if (index < leftTotal + h.count) {
                return h.key;
            } else {
                index -= leftTotal + h.count;
                h = h.right;
            }
        }
        throw new IndexOutOfBoundsException("index out of range");
    }

    private static <T> int rankOf(Node<T> h, T item, Order<T> order) {
        int result = 0;
        while (h != null) {
            int comparison = order.compare(item, h.key);
            if (comparison <= 0) {
                h = h.left;
            } else {
                result += total(h.left) + h.count;
                h = h.right;
            }
        }
        return result;
    }
}
