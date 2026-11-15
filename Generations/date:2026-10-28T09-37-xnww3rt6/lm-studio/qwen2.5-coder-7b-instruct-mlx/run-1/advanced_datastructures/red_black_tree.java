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
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
    }

    private static class Node<T> {
        T item;
        boolean color;
        Node<T> left, right;

        Node(T item) {
            this.item = item;
            color = RED;
            left = right = null;
        }
    }

    private boolean isRed(Node<T> node) {
        if (node == null) return BLACK;
        return node.color == RED;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> right = node.right;
        node.right = right.left;
        right.left = node;
        right.color = node.color;
        node.color = RED;
        return right;
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> left = node.left;
        node.left = left.right;
        left.right = node;
        left.color = node.color;
        node.color = RED;
        return left;
    }

    private void flipColors(Node<T> node) {
        if (node != null && node.left != null && node.right != null) {
            node.color = !node.color;
            node.left.color = !node.left.color;
            node.right.color = !node.right.color;
        }
    }

    private Node<T> balance(Node<T> node) {
        if (node == null || !isRed(node.left) && !isRed(node.right)) return node;

        if (isRed(node.right.left)) {
            node.right = rotateRight(node.right);
            node = rotateLeft(node);
        }

        if (isRed(node.left) && isRed(node.left.left)) {
            node = rotateRight(node);
        }

        if (isRed(node.left) && isRed(node.right)) {
            flipColors(node);
        }

        return node;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        root = add(root, item);
        if (root != null) {
            root.color = BLACK;
            return true;
        }
        return false;
    }

    private Node<T> add(Node<T> node, T item) {
        if (node == null) return new Node<>(item);
        int cmp = order.compare(item, node.item);
        if (cmp < 0) node.left = add(node.left, item);
        else if (cmp > 0) node.right = add(node.right, item);

        return balance(node);
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        boolean result = remove(root, item);
        if (root != null) {
            root.color = BLACK;
            return result;
        }
        return false;
    }

    private Node<T> remove(Node<T> node, T item) {
        if (node == null) return null;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) node.left = remove(node.left, item);
        else if (cmp > 0) node.right = remove(node.right, item);
        else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;

            Node<T> min = findMin(node.right);
            node.item = min.item;
            node.right = removeMin(node.right);
        }

        return balance(node);
    }

    private Node<T> findMin(Node<T> node) {
        while (node.left != null) node = node.left;
        return node;
    }

    private Node<T> removeMin(Node<T> node) {
        if (node.left == null) return node.right;
        node.left = removeMin(node.left);
        return balance(node);
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        return contains(root, item);
    }

    private boolean contains(Node<T> node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) return contains(node.left, item);
        else if (cmp > 0) return contains(node.right, item);
        return true;
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException("Cannot call first on empty RedBlackTree");
        return findMin(root).item;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException("Cannot call last on empty RedBlackTree");
        Node<T> node = root;
        while (node.right != null) node = node.right;
        return node.item;
    }

    public synchronized int size() {
        return size(root);
    }

    private int size(Node<T> node) {
        if (node == null) return 0;
        return 1 + size(node.left) + size(node.right);
    }

    public synchronized Object[] snapshot() {
        return snapshot(root, new ArrayList<>()).toArray();
    }

    private List<T> snapshot(Node<T> node, List<T> list) {
        if (node != null) {
            snapshot(node.left, list);
            list.add(node.item);
            snapshot(node.right, list);
        }
        return list;
    }
}
