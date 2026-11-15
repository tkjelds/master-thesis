import java.util.concurrent.atomic.AtomicInteger;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private class Node {
        T key;
        Node left, right;
        boolean color;
        int size;

        Node(T key) {
            this.key = key;
            this.color = RED;
            this.size = 1;
        }
    }

    private Node root;
    private Order<T> order;

    public RedBlackTree(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        root = add(root, item);
        if (root != null) root.color = BLACK;
        return true; // No duplicate elements allowed
    }

    private Node add(Node node, T item) {
        if (node == null) return new Node(item);

        int cmp = order.compare(item, node.key);
        if (cmp < 0) {
            node.left = add(node.left, item);
        } else if (cmp > 0) {
            node.right = add(node.right, item);
        } else {
            return null; // Duplicate elements not allowed
        }

        node.size = 1 + size(node.left) + size(node.right);
        return balance(node);
    }

    private Node balance(Node node) {
        if (isRed(node.right) && !isRed(node.left)) node = rotateLeft(node);
        if (isRed(node.left) && isRed(node.left.left)) node = rotateRight(node);
        if (isRed(node.left) && isRed(node.right)) {
            node.color = RED;
            node.left.color = BLACK;
            node.right.color = BLACK;
        }

        return node;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        if (!contains(item)) return false;
        root = remove(root, item);
        if (root != null) root.color = BLACK;
        return true;
    }

    private Node remove(Node node, T item) {
        if (order.compare(item, node.key) < 0) {
            node.left = remove(node.left, item);
        } else if (order.compare(item, node.key) > 0) {
            node.right = remove(node.right, item);
        } else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;

            Node successor = min(node.right);
            node.key = successor.key;
            node.right = remove(node.right, successor.key);
        }

        if (node != null) node.size = 1 + size(node.left) + size(node.right);
        return balance(node);
    }

    private Node min(Node node) {
        while (node.left != null) node = node.left;
        return node;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        return contains(root, item);
    }

    private boolean contains(Node node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.key);
        if (cmp < 0) return contains(node.left, item);
        else if (cmp > 0) return contains(node.right, item);
        else return true;
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException("Cannot call first on empty tree");
        return min(root).key;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException("Cannot call last on empty tree");
        Node current = root;
        while (current.right != null) current = current.right;
        return current.key;
    }

    public synchronized int size() {
        if (root == null) return 0;
        return root.size;
    }

    public synchronized Object[] snapshot() {
        Node[] array = new Node[size()];
        int i = 0;
        inOrderTraversal(root, array, i);
        return cloneArray(array);
    }

    private int inOrderTraversal(Node node, Node[] array, int i) {
        if (node == null) return i;
        i = inOrderTraversal(node.left, array, i);
        array[i++] = node;
        return inOrderTraversal(node.right, array, i);
    }

    private Object[] cloneArray(Node[] array) {
        Object[] copy = new Object[array.length];
        for (int i = 0; i < array.length; i++) copy[i] = array[i].key;
        return copy;
    }

    private Node rotateLeft(Node node) {
        Node right = node.right;
        node.right = right.left;
        right.left = node;
        right.color = node.color;
        node.color = RED;
        node.size = size(node.left) + size(node.right) + 1;
        right.size = size(right.left) + size(right.right) + 1;
        return right;
    }

    private Node rotateRight(Node node) {
        Node left = node.left;
        node.left = left.right;
        left.right = node;
        left.color = node.color;
        node.color = RED;
        node.size = size(node.left) + size(node.right) + 1;
        left.size = size(left.left) + size(left.right) + 1;
        return left;
    }

    private boolean isRed(Node node) {
        if (node == null) return false;
        return node.color == RED;
    }

    private int size(Node node) {
        if (node == null) return 0;
        return node.size;
    }
}
