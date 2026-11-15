import java.io.Serializable;

interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> implements Serializable {
    @interface ThreadSafe {}

    private static final long serialVersionUID = 1L;
    private transient Node root;
    private Order<T> order;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    private static class Node {
        T value;
        Node left, right;

        Node(T value) {
            this.value = value;
        }
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            root = addRecursive(root, item);
            return true;
        }
    }

    private Node addRecursive(Node current, T item) {
        if (current == null) return new Node(item);
        int cmp = order.compare(current.value, item);
        if (cmp > 0) {
            current.left = addRecursive(current.left, item);
        } else if (cmp < 0) {
            current.right = addRecursive(current.right, item);
        }
        return current;
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            boolean found = findRecursive(root, item);
            if (found) root = removeRecursive(root, item);
            return found;
        }
    }

    private boolean findRecursive(Node current, T item) {
        if (current == null) return false;
        int cmp = order.compare(current.value, item);
        if (cmp == 0) return true;
        else if (cmp > 0) return findRecursive(current.left, item);
        else return findRecursive(current.right, item);
    }

    private Node removeRecursive(Node current, T item) {
        if (current == null) return null;
        int cmp = order.compare(current.value, item);
        if (cmp > 0) {
            current.left = removeRecursive(current.left, item);
        } else if (cmp < 0) {
            current.right = removeRecursive(current.right, item);
        } else {
            if (current.left == null) return current.right;
            else if (current.right == null) return current.left;
            T smallestValue = findSmallestValue(current.right);
            current.value = smallestValue;
            current.right = removeRecursive(current.right, smallestValue);
        }
        return current;
    }

    private T findSmallestValue(Node root) {
        if (root.left == null) return root.value;
        else return findSmallestValue(root.left);
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        synchronized (this) {
            return findRecursive(root, item);
        }
    }

    public T first() {
        synchronized (this) {
            Node current = root;
            while (current.left != null) {
                current = current.left;
            }
            if (current == null) throw new IllegalStateException();
            return current.value;
        }
    }

    public T last() {
        synchronized (this) {
            Node current = root;
            while (current.right != null) {
                current = current.right;
            }
            if (current == null) throw new IllegalStateException();
            return current.value;
        }
    }

    public int size() {
        synchronized (this) {
            return sizeRecursive(root);
        }
    }

    private int sizeRecursive(Node current) {
        if (current == null) return 0;
        return 1 + sizeRecursive(current.left) + sizeRecursive(current.right);
    }

    @SuppressWarnings("unchecked")
    public Object[] snapshot() {
        synchronized (this) {
            Node current = root;
            if (current == null) return new Object[0];
            int size = size();
            Object[] array = new Object[size];
            populateArray(current, array, 0);
            return array;
        }
    }

    private int populateArray(Node current, Object[] array, int index) {
        if (current == null) return index;
        index = populateArray(current.left, array, index);
        array[index++] = current.value;
        return populateArray(current.right, array, index);
    }
}
