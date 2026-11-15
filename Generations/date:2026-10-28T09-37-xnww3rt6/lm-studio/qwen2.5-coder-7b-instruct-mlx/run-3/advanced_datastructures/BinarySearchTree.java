@Order.ThreadSafe
class Order<T> {
    @interface ThreadSafe {}

    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
    }

    private static class Node<T> {
        T element;
        Node<T> left, right;

        Node(T element) {
            this.element = element;
            left = right = null;
        }
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        synchronized (this) {
            root = addRecursive(root, item);
            return true;
        }
    }

    private Node<T> addRecursive(Node<T> node, T item) {
        if (node == null) return new Node<>(item);
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            node.left = addRecursive(node.left, item);
        } else if (cmp > 0) {
            node.right = addRecursive(node.right, item);
        }
        return node;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        synchronized (this) {
            root = removeRecursive(root, item);
            return true;
        }
    }

    private Node<T> removeRecursive(Node<T> node, T item) {
        if (node == null) return null;
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            node.left = removeRecursive(node.left, item);
        } else if (cmp > 0) {
            node.right = removeRecursive(node.right, item);
        } else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            T smallest = findSmallest(node.right);
            node.element = smallest;
            node.right = removeRecursive(node.right, smallest);
        }
        return node;
    }

    private T findSmallest(Node<T> node) {
        while (node.left != null) node = node.left;
        return node.element;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("Item cannot be null");
        synchronized (this) {
            return containsRecursive(root, item);
        }
    }

    private boolean containsRecursive(Node<T> node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.element);
        if (cmp == 0) return true;
        else if (cmp < 0) return containsRecursive(node.left, item);
        else return containsRecursive(node.right, item);
    }

    public T first() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException("Tree is empty");
            return findSmallest(root);
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException("Tree is empty");
            return findLargest(root);
        }
    }

    private T findLargest(Node<T> node) {
        while (node.right != null) node = node.right;
        return node.element;
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            return inOrderTraversal(root);
        }
    }

    private Object[] inOrderTraversal(Node<T> node) {
        if (node == null) return new Object[0];
        int size = 1 + size(node.left) + size(node.right);
        Object[] result = new Object[size];
        int i = 0;
        System.arraycopy(inOrderTraversal(node.left), 0, result, 0, size(node.left));
        result[i++] = node.element;
        System.arraycopy(inOrderTraversal(node.right), 0, result, i, size(node.right));
        return result;
    }

    private int size(Node<T> node) {
        if (node == null) return 0;
        return 1 + size(node.left) + size(node.right);
    }
}
