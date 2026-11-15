import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private Node<T> root;
    private Order<T> order;

    public RedBlackTree(Order<T> order) {
        this.order = order;
    }

    private static <T> Node<T> rotateLeft(Node<T> h) {
        Node<T> x = h.right;
        h.right = x.left;
        x.left = h;
        x.color = h.color;
        h.color = RED;
        return x;
    }

    private static <T> Node<T> rotateRight(Node<T> h) {
        Node<T> x = h.left;
        h.left = x.right;
        x.right = h;
        x.color = h.color;
        h.color = RED;
        return x;
    }

    private static <T> Node<T> flipColors(Node<T> h) {
        h.color = !h.color;
        h.left.color = !h.left.color;
        h.right.color = !h.right.color;
        return h;
    }

    private boolean isRed(Node<T> x) {
        if (x == null) return false;
        return x.color == RED;
    }

    private Node<T> put(Node<T> h, T key) {
        if (h == null) return new Node<>(key);

        int cmp = order.compare(key, h.key);
        if (cmp < 0) h.left = put(h.left, key);
        else if (cmp > 0) h.right = put(h.right, key);
        else h.val = key; // Duplicate keys are not allowed

        if (isRed(h.right) && !isRed(h.left)) h = rotateLeft(h);
        if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
        if (isRed(h.left) && isRed(h.right)) flipColors(h);

        return h;
    }

    private Node<T> min(Node<T> x) {
        if (x.left == null) return x;
        return min(x.left);
    }

    private Node<T> deleteMin(Node<T> h) {
        if (h.left == null) return null;
        if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
        h.left = deleteMin(h.left);
        return balance(h);
    }

    private Node<T> delete(Node<T> h, T key) {
        if (order.compare(key, h.key) < 0) {
            if (!isRed(h.left) && !isRed(h.left.left)) h = moveRedLeft(h);
            h.left = delete(h.left, key);
        } else {
            if (isRed(h.left)) h = rotateRight(h);
            if (order.compare(key, h.key) == 0 && h.right == null) return null;
            if (!isRed(h.right) && !isRed(h.right.left)) h = moveRedRight(h);
            if (order.compare(key, h.key) == 0) {
                Node<T> x = min(h.right);
                h.key = x.key;
                h.val = x.val;
                h.right = deleteMin(h.right);
            } else {
                h.right = delete(h.right, key);
            }
        }
        return balance(h);
    }

    private Node<T> moveRedLeft(Node<T> h) {
        flipColors(h);
        if (isRed(h.right.left)) {
            h.right = rotateRight(h.right);
            h = rotateLeft(h);
        }
        return h;
    }

    private Node<T> moveRedRight(Node<T> h) {
        flipColors(h);
        if (isRed(h.left.left)) {
            h = rotateRight(h);
        }
        return h;
    }

    private Node<T> balance(Node<T> h) {
        if (isRed(h.right)) h = rotateLeft(h);
        if (isRed(h.left) && isRed(h.left.left)) h = rotateRight(h);
        if (isRed(h.left) && isRed(h.right)) flipColors(h);

        return h;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        root = put(root, item);
        root.color = BLACK;
        return true; // Duplicates are not allowed
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        root = delete(root, item);
        return true;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> x = root;
        while (x != null) {
            int cmp = order.compare(item, x.key);
            if (cmp < 0) x = x.left;
            else if (cmp > 0) x = x.right;
            else return true;
        }
        return false;
    }

    public T first() {
        if (root == null) throw new IllegalStateException();
        return min(root).key;
    }

    public T last() {
        if (root == null) throw new IllegalStateException();
        Node<T> x = root;
        while (x.right != null) x = x.right;
        return x.key;
    }

    public int size() {
        return size(root);
    }

    private int size(Node<T> x) {
        if (x == null) return 0;
        return 1 + size(x.left) + size(x.right);
    }

    public Object[] snapshot() {
        return snapshot(root);
    }

    private Object[] snapshot(Node<T> x) {
        if (x == null) return new Object[0];
        Object[] left = snapshot(x.left);
        Object[] right = snapshot(x.right);
        Object[] result = new Object[left.length + 1 + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        result[left.length] = x.key;
        System.arraycopy(right, 0, result, left.length + 1, right.length);
        return result;
    }

    private static class Node<T> {
        T key;
        Object val; // Unused, but needed for the structure
        Node<T> left, right;
        boolean color;

        Node(T key) {
            this.key = key;
            this.color = RED;
        }
    }
}
