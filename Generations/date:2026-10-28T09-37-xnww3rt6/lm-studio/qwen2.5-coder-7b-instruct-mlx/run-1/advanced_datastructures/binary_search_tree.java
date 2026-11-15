@BinarySearchTree.ThreadSafe
class BinarySearchTree<T> {
    @interface ThreadSafe {}

    interface Order<T> {
        int compare(T left, T right);
    }

    private static class Node<T> {
        T element;
        Node<T> left, right;

        Node(T element) {
            this.element = element;
            left = null;
            right = null;
        }
    }

    private Node<T> root;
    private Order<T> order;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
        root = null;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        if (root == null) {
            root = new Node<>(item);
            return true;
        }
        Node<T> current = root, parent = null;
        while (current != null) {
            parent = current;
            int cmp = order.compare(item, current.element);
            if (cmp == 0) return false;
            if (cmp < 0) current = current.left;
            else current = current.right;
        }
        if (order.compare(item, parent.element) < 0) parent.left = new Node<>(item);
        else parent.right = new Node<>(item);
        return true;
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        Node<T> current = root, parent = null;
        while (current != null && order.compare(item, current.element) != 0) {
            parent = current;
            if (order.compare(item, current.element) < 0) current = current.left;
            else current = current.right;
        }
        if (current == null) return false;
        if (current.left == null && current.right == null) {
            if (parent == null) root = null;
            else if (order.compare(item, parent.element) < 0) parent.left = null;
            else parent.right = null;
        } else if (current.left == null) {
            if (parent == null) root = current.right;
            else if (order.compare(item, parent.element) < 0) parent.left = current.right;
            else parent.right = current.right;
        } else if (current.right == null) {
            if (parent == null) root = current.left;
            else if (order.compare(item, parent.element) < 0) parent.left = current.left;
            else parent.right = current.left;
        } else {
            Node<T> successor = current.right, successorParent = current;
            while (successor.left != null) {
                successorParent = successor;
                successor = successor.left;
            }
            if (successorParent == current) {
                root = successor;
            } else {
                successorParent.left = successor.right;
            }
            successor.left = current.left;
            successor.right = current.right;
        }
        return true;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        Node<T> current = root;
        while (current != null && order.compare(item, current.element) != 0) {
            if (order.compare(item, current.element) < 0) current = current.left;
            else current = current.right;
        }
        return current != null;
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException("Tree is empty");
        Node<T> current = root;
        while (current.left != null) current = current.left;
        return current.element;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException("Tree is empty");
        Node<T> current = root;
        while (current.right != null) current = current.right;
        return current.element;
    }

    public synchronized int size() {
        if (root == null) return 0;
        return sizeHelper(root);
    }

    private int sizeHelper(Node<T> node) {
        if (node == null) return 0;
        return 1 + sizeHelper(node.left) + sizeHelper(node.right);
    }

    public synchronized Object[] snapshot() {
        if (root == null) return new Object[0];
        int size = size();
        Object[] snapshotArray = new Object[size];
        generateSnapshot(root, snapshotArray);
        return snapshotArray;
    }

    private int generateSnapshot(Node<T> node, Object[] array) {
        if (node == null) return 0;
        int index = generateSnapshot(node.left, array);
        array[index] = node.element;
        return generateSnapshot(node.right, array) + 1;
    }
}
