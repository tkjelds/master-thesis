import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T value;
        Node<T> left, right;

        Node(T value) {
            this.value = value;
        }
    }

    private Order<T> order;
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
        if (order == null || order.compare(null, null) != 0) {
            throw new NullPointerException("Order must not be null and must handle nulls correctly");
        }
        this.order = order;
        this.root = null;
        this.size = 0;
    }

    public boolean add(T item) {
        if (item == null) throw new NullPointerException("Item must not be null");
        synchronized (this) {
            Node<T> current = root;
            Node<T> parent = null;
            while (current != null) {
                int cmp = order.compare(item, current.value);
                if (cmp == 0) return false;
                parent = current;
                current = cmp < 0 ? current.left : current.right;
            }
            Node<T> newNode = new Node<>(item);
            if (parent == null) root = newNode;
            else if (order.compare(item, parent.value) < 0) parent.left = newNode;
            else parent.right = newNode;
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) throw new NullPointerException("Item must not be null");
        synchronized (this) {
            Node<T> current = root;
            Node<T> parent = null;
            while (current != null) {
                int cmp = order.compare(item, current.value);
                if (cmp == 0) break;
                parent = current;
                current = cmp < 0 ? current.left : current.right;
            }
            if (current == null) return false;
            deleteNode(current, parent);
            size--;
            return true;
        }
    }

    private void deleteNode(Node<T> node, Node<T> parent) {
        if (node.left == null && node.right == null) { // Leaf node
            if (parent == null) root = null;
            else if (parent.left == node) parent.left = null;
            else parent.right = null;
        } else if (node.left == null) { // One child - right
            if (parent == null) root = node.right;
            else if (parent.left == node) parent.left = node.right;
            else parent.right = node.right;
        } else if (node.right == null) { // One child - left
            if (parent == null) root = node.left;
            else if (parent.left == node) parent.left = node.left;
            else parent.right = node.left;
        } else { // Two children
            Node<T> successor = findMin(node.right);
            node.value = successor.value;
            deleteNode(successor, node);
        }
    }

    private Node<T> findMin(Node<T> node) {
        while (node.left != null) node = node.left;
        return node;
    }

    public boolean contains(T item) {
        if (item == null) throw new NullPointerException("Item must not be null");
        synchronized (this) {
            Node<T> current = root;
            while (current != null) {
                int cmp = order.compare(item, current.value);
                if (cmp == 0) return true;
                current = cmp < 0 ? current.left : current.right;
            }
            return false;
        }
    }

    public T first() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException("Tree is empty");
            Node<T> current = root;
            while (current.left != null) current = current.left;
            return current.value;
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) throw new IllegalStateException("Tree is empty");
            Node<T> current = root;
            while (current.right != null) current = current.right;
            return current.value;
        }
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    @SuppressWarnings("unchecked")
    public Object[] snapshot() {
        synchronized (this) {
            if (root == null) return new Object[0];
            Node<T>[] nodes = treeToArray(root);
            int i = 0;
            for (Node<T> node : nodes) {
                if (node != null) nodes[i++] = node.value;
            }
            return java.util.Arrays.copyOf(nodes, i);
        }
    }

    @SuppressWarnings("unchecked")
    private Node<T>[] treeToArray(Node<T> root) {
        int height = getHeight(root);
        @SuppressWarnings("unchecked")
        Node<T>[] result = new Node[1 << height];
        fillArray(result, 0, root);
        return result;
    }

    private int getHeight(Node<T> node) {
        if (node == null) return 0;
        return Math.max(getHeight(node.left), getHeight(node.right)) + 1;
    }

    private void fillArray(Node<T>[] result, int index, Node<T> node) {
        if (node == null) return;
        result[index] = node;
        fillArray(result, 2 * index + 1, node.left);
        fillArray(result, 2 * index + 2, node.right);
    }

    public interface Order<T> {
        int compare(T left, T right);
    }
}
