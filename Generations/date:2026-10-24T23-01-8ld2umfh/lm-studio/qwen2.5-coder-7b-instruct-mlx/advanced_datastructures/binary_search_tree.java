interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private Node root;
    private final Order<T> order;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
        this.root = null;
    }

    private static class Node {
        T value;
        Node left, right;

        Node(T value) {
            this.value = value;
            this.left = null;
            this.right = null;
        }
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        if (root == null) {
            root = new Node(item);
            return true;
        }
        if (contains(item)) return false;
        insert(root, item);
        return true;
    }

    private synchronized void insert(Node node, T item) {
        int cmp = order.compare(item, node.value);
        if (cmp < 0) {
            if (node.left == null) {
                node.left = new Node(item);
            } else {
                insert(node.left, item);
            }
        } else {
            if (node.right == null) {
                node.right = new Node(item);
            } else {
                insert(node.right, item);
            }
        }
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        if (!contains(item)) return false;
        root = delete(root, item);
        return true;
    }

    private synchronized Node delete(Node node, T item) {
        if (node == null) return null;
        int cmp = order.compare(item, node.value);
        if (cmp < 0) {
            node.left = delete(node.left, item);
        } else if (cmp > 0) {
            node.right = delete(node.right, item);
        } else {
            if (node.left == null) return node.right;
            else if (node.right == null) return node.left;
            Node temp = minValueNode(node.right);
            node.value = temp.value;
            node.right = delete(node.right, temp.value);
        }
        return node;
    }

    private synchronized Node minValueNode(Node node) {
        while (node.left != null) node = node.left;
        return node;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        return find(root, item);
    }

    private synchronized boolean find(Node node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.value);
        if (cmp == 0) return true;
        else if (cmp < 0) return find(node.left, item);
        else return find(node.right, item);
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException();
        return minValue(root);
    }

    private synchronized T minValue(Node node) {
        while (node.left != null) node = node.left;
        return node.value;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException();
        return maxValue(root);
    }

    private synchronized T maxValue(Node node) {
        while (node.right != null) node = node.right;
        return node.value;
    }

    public synchronized int size() {
        return count(root);
    }

    private synchronized int count(Node node) {
        if (node == null) return 0;
        return 1 + count(node.left) + count(node.right);
    }

    public synchronized Object[] snapshot() {
        Object[] snapshot = new Object[size()];
        fillArray(root, snapshot, 0);
        return snapshot;
    }

    private synchronized int fillArray(Node node, Object[] array, int index) {
        if (node == null) return index;
        index = fillArray(node.left, array, index);
        array[index++] = node.value;
        index = fillArray(node.right, array, index);
        return index;
    }
}
