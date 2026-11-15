interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T value;
        Node<T> left, right;

        Node(T value) {
            this.value = value;
        }
    }

    private Node<T> root;
    private Order<T> order;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> newNode = new Node<>(item);
        if (root == null) {
            root = newNode;
            return true;
        }
        Node<T> current = root;
        while (true) {
            int cmp = order.compare(item, current.value);
            if (cmp == 0) return false;
            if (cmp < 0) {
                if (current.left == null) {
                    current.left = newNode;
                    return true;
                }
                current = current.left;
            } else {
                if (current.right == null) {
                    current.right = newNode;
                    return true;
                }
                current = current.right;
            }
        }
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> parent = null;
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.value);
            if (cmp == 0) break;
            parent = current;
            current = cmp < 0 ? current.left : current.right;
        }
        if (current == null) return false;

        Node<T> child = null;
        if (current.left == null) {
            child = current.right;
        } else if (current.right == null) {
            child = current.left;
        } else {
            Node<T> successor = current.right;
            parent = current;
            while (successor.left != null) {
                parent = successor;
                successor = successor.left;
            }
            current.value = successor.value;
            child = successor.right;
        }

        if (parent == null) {
            root = child;
        } else if (current == parent.left) {
            parent.left = child;
        } else {
            parent.right = child;
        }
        return true;
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> current = root;
        while (current != null) {
            int cmp = order.compare(item, current.value);
            if (cmp == 0) return true;
            current = cmp < 0 ? current.left : current.right;
        }
        return false;
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.left != null) {
            current = current.left;
        }
        return current.value;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.right != null) {
            current = current.right;
        }
        return current.value;
    }

    public synchronized int size() {
        return root == null ? 0 : countSize(root);
    }

    private int countSize(Node<T> node) {
        if (node == null) return 0;
        return 1 + countSize(node.left) + countSize(node.right);
    }

    public synchronized Object[] snapshot() {
        int size = this.size();
        Object[] array = new Object[size];
        if (root == null) return array;
        fillSnapshot(root, array, 0);
        return array;
    }

    private int fillSnapshot(Node<T> node, Object[] array, int index) {
        if (node == null) return index;
        index = fillSnapshot(node.left, array, index);
        array[index++] = node.value;
        return fillSnapshot(node.right, array, index);
    }
}
