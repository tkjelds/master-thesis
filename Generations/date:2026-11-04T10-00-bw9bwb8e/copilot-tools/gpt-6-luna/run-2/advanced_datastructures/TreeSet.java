@TreeSet.ThreadSafe
public class TreeSet<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

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

    public static final class Snapshot<E> {
        private final Node<E> root;
        private final Order<E> order;
        private final Object monitor;

        private Snapshot(Node<E> root, Order<E> order, Object monitor) {
            this.root = root;
            this.order = order;
            this.monitor = monitor;
        }

        public int size() {
            return count(root);
        }

        public E get(int index) {
            if (index < 0 || index >= count(root)) {
                throw new IndexOutOfBoundsException();
            }
            Node<E> current = root;
            int position = index;
            while (current != null) {
                int leftCount = count(current.left);
                if (position < leftCount) {
                    current = current.left;
                } else if (position == leftCount) {
                    return current.item;
                } else {
                    position -= leftCount + 1;
                    current = current.right;
                }
            }
            throw new IndexOutOfBoundsException();
        }

        public boolean contains(E item) {
            if (item == null) {
                throw new NullPointerException();
            }
            synchronized (monitor) {
                return find(root, item, order) != null;
            }
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
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
            boolean[] added = new boolean[1];
            Node<T> updated = insert(root, item, added);
            if (!added[0]) {
                return false;
            }
            root = recolor(updated, false);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            if (find(root, item, order) == null) {
                return false;
            }
            Node<T> startingRoot = root;
            if (!isRed(startingRoot.left) && !isRed(startingRoot.right)) {
                startingRoot = recolor(startingRoot, true);
            }
            Node<T> updated = delete(startingRoot, item);
            root = updated == null ? null : recolor(updated, false);
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            return find(root, item, order) != null;
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
                throw new IndexOutOfBoundsException();
            }
            Node<T> current = root;
            int position = index;
            while (current != null) {
                int leftCount = count(current.left);
                if (position < leftCount) {
                    current = current.left;
                } else if (position == leftCount) {
                    return current.item;
                } else {
                    position -= leftCount + 1;
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
            return new Snapshot<T>(root, order, monitor);
        }
    }

    private static int count(Node<?> node) {
        return node == null ? 0 : node.count;
    }

    private static boolean isRed(Node<?> node) {
        return node != null && node.red;
    }

    private static <E> Node<E> node(E item, Node<E> left, Node<E> right, boolean red) {
        return new Node<E>(item, left, right, red);
    }

    private static <E> Node<E> recolor(Node<E> node, boolean red) {
        if (node == null || node.red == red) {
            return node;
        }
        return TreeSet.node(node.item, node.left, node.right, red);
    }

    private static <E> Node<E> rotateLeft(Node<E> h) {
        Node<E> x = h.right;
        Node<E> newLeft = node(h.item, h.left, x.left, true);
        return node(x.item, newLeft, x.right, h.red);
    }

    private static <E> Node<E> rotateRight(Node<E> h) {
        Node<E> x = h.left;
        Node<E> newRight = node(h.item, x.right, h.right, true);
        return node(x.item, x.left, newRight, h.red);
    }

    private static <E> Node<E> flipColors(Node<E> h) {
        Node<E> left = h.left == null ? null : recolor(h.left, !h.left.red);
        Node<E> right = h.right == null ? null : recolor(h.right, !h.right.red);
        return node(h.item, left, right, !h.red);
    }

    private static <E> Node<E> balanceAfterInsert(Node<E> h) {
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

    private Node<T> insert(Node<T> h, T item, boolean[] added) {
        if (h == null) {
            added[0] = true;
            return node(item, null, null, true);
        }
        int comparison = order.compare(item, h.item);
        if (comparison < 0) {
            h = node(h.item, insert(h.left, item, added), h.right, h.red);
        } else if (comparison > 0) {
            h = node(h.item, h.left, insert(h.right, item, added), h.red);
        } else {
            return h;
        }
        return balanceAfterInsert(h);
    }

    private static <E> Node<E> find(Node<E> root, E item, Order<E> order) {
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

    private static <E> Node<E> moveRedLeft(Node<E> h) {
        h = flipColors(h);
        if (h.right != null && isRed(h.right.left)) {
            Node<E> right = rotateRight(h.right);
            h = node(h.item, h.left, right, h.red);
            h = rotateLeft(h);
            h = flipColors(h);
        }
        return h;
    }

    private static <E> Node<E> moveRedRight(Node<E> h) {
        h = flipColors(h);
        if (h.left != null && isRed(h.left.left)) {
            h = rotateRight(h);
            h = flipColors(h);
        }
        return h;
    }

    private static <E> Node<E> fixUp(Node<E> h) {
        if (isRed(h.right)) {
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

    private Node<T> delete(Node<T> h, T item) {
        if (order.compare(item, h.item) < 0) {
            if (h.left != null) {
                if (!isRed(h.left) && !isRed(h.left.left)) {
                    h = moveRedLeft(h);
                }
                h = node(h.item, delete(h.left, item), h.right, h.red);
            }
        } else {
            if (isRed(h.left)) {
                h = rotateRight(h);
            }
            if (order.compare(item, h.item) == 0 && h.right == null) {
                return null;
            }
            if (h.right != null) {
                if (!isRed(h.right) && !isRed(h.right.left)) {
                    h = moveRedRight(h);
                }
                if (order.compare(item, h.item) == 0) {
                    Node<T> successor = minimum(h.right);
                    h = node(successor.item, h.left, deleteMinimum(h.right), h.red);
                } else {
                    h = node(h.item, h.left, delete(h.right, item), h.red);
                }
            }
        }
        return fixUp(h);
    }

    private static <E> Node<E> minimum(Node<E> h) {
        Node<E> current = h;
        while (current.left != null) {
            current = current.left;
        }
        return current;
    }

    private static <E> Node<E> deleteMinimum(Node<E> h) {
        if (h.left == null) {
            return null;
        }
        if (!isRed(h.left) && !isRed(h.left.left)) {
            h = moveRedLeft(h);
        }
        h = node(h.item, deleteMinimum(h.left), h.right, h.red);
        return fixUp(h);
    }
}
