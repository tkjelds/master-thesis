@ConcurrentBinarySearchTree.ThreadSafe
public class ConcurrentBinarySearchTree {
    @interface ThreadSafe {}

    private Node root;
    private static final Object lock = new Object();

    public ConcurrentBinarySearchTree() {
        this.root = null;
    }

    private static class Node {
        int value;
        Node left, right;

        Node(int item) {
            value = item;
            left = right = null;
        }
    }

    public boolean search(int key) {
        synchronized (lock) {
            Node current = root;
            while (current != null) {
                if (key == current.value)
                    return true;
                else if (key < current.value)
                    current = current.left;
                else
                    current = current.right;
            }
            return false;
        }
    }

    public void insert(int key) {
        synchronized (lock) {
            Node newNode = new Node(key);
            if (root == null)
                root = newNode;
            else {
                Node current = root, parent = null;
                while (current != null) {
                    parent = current;
                    if (key < current.value)
                        current = current.left;
                    else
                        current = current.right;
                }
                if (key < parent.value)
                    parent.left = newNode;
                else
                    parent.right = newNode;
            }
        }
    }

    public void delete(int key) {
        synchronized (lock) {
            root = deleteRecursive(root, key);
        }
    }

    private Node deleteRecursive(Node root, int key) {
        if (root == null)
            return root;

        if (key < root.value)
            root.left = deleteRecursive(root.left, key);
        else if (key > root.value)
            root.right = deleteRecursive(root.right, key);
        else {
            if (root.left == null)
                return root.right;
            else if (root.right == null)
                return root.left;

            root.value = minValue(root.right);
            root.right = deleteRecursive(root.right, root.value);
        }
        return root;
    }

    private int minValue(Node node) {
        int minv = node.value;
        while (node.left != null) {
            minv = node.left.value;
            node = node.left;
        }
        return minv;
    }

    public void inOrder() {
        synchronized (lock) {
            inOrderRecursive(root);
        }
    }

    private void inOrderRecursive(Node node) {
        if (node != null) {
            inOrderRecursive(node.left);
            System.out.print(node.value + " ");
            inOrderRecursive(node.right);
        }
    }
}
