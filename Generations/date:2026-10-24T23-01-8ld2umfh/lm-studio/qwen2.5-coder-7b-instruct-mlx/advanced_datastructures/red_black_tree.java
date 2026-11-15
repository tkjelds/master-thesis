import java.util.NoSuchElementException;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = false;
    private static final boolean BLACK = true;

    private class Node {
        T item;
        Node left, right;
        boolean color;

        Node(T item) {
            this.item = item;
            left = right = null;
            color = RED;
        }
    }

    private Node root;

    public RedBlackTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            root = add(root, item);
            if (root != null) root.color = BLACK;
        }
        return true; // Always returns true for simplicity
    }

    private Node add(Node node, T item) {
        if (node == null) return new Node(item);

        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = add(node.left, item);
        } else if (cmp > 0) {
            node.right = add(node.right, item);
        } else {
            return node; // Duplicate items are not allowed
        }

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

    private Node rotateLeft(Node node) {
        Node x = node.right;
        node.right = x.left;
        x.left = node;
        x.color = node.color;
        node.color = RED;
        return x;
    }

    private Node rotateRight(Node node) {
        Node x = node.left;
        node.left = x.right;
        x.right = node;
        x.color = node.color;
        node.color = RED;
        return x;
    }

    private boolean isRed(Node node) {
        return node != null && !node.color;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            root = remove(root, item);
            if (root != null) root.color = BLACK;
        }
        return true; // Always returns true for simplicity
    }

    private Node remove(Node node, T item) {
        if (node == null) return null;

        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = remove(node.left, item);
        } else if (cmp > 0) {
            node.right = remove(node.right, item);
        } else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;

            Node next = minNode(node.right);
            node.item = next.item;
            node.right = remove(node.right, item);
        }

        return balance(node);
    }

    private Node minNode(Node node) {
        while (node.left != null) node = node.left;
        return node;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            return contains(root, item);
        }
    }

    private boolean contains(Node node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) return contains(node.left, item);
        if (cmp > 0) return contains(node.right, item);
        return true;
    }

    public T first() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException();
            return minNode(root).item;
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException();
            return maxNode(root).item;
        }
    }

    private Node maxNode(Node node) {
        while (node.right != null) node = node.right;
        return node;
    }

    public int size() {
        synchronized (this) {
            return size(root);
        }
    }

    private int size(Node node) {
        if (node == null) return 0;
        return 1 + size(node.left) + size(node.right);
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[size()];
            inOrderTraversal(root, 0, result);
            return result;
        }
    }

    private void inOrderTraversal(Node node, int index, Object[] result) {
        if (node == null) return;
        inOrderTraversal(node.left, index, result);
        result[index] = node.item;
        inOrderTraversal(node.right, index + 1, result);
    }
}
