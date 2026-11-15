@BinarySearchTree.ThreadSafe
public class BinarySearchTree {
    @interface ThreadSafe {}

    private TreeNode root;

    public static class TreeNode {
        int value;
        TreeNode left, right;

        public TreeNode(int item) {
            value = item;
            left = right = null;
        }
    }

    public boolean search(int key) {
        synchronized (this) {
            return searchUtil(root, key);
        }
    }

    private boolean searchUtil(TreeNode node, int key) {
        if (node == null) {
            return false;
        }

        synchronized (this) {
            if (key == node.value)
                return true;

            if (key < node.value) {
                synchronized (this) {
                    return searchUtil(node.left, key);
                }
            } else {
                synchronized (this) {
                    return searchUtil(node.right, key);
                }
            }
        }
    }

    public void insert(int key) {
        synchronized (this) {
            root = insertUtil(root, key);
        }
    }

    private TreeNode insertUtil(TreeNode node, int key) {
        if (node == null) {
            node = new TreeNode(key);
            return node;
        }

        synchronized (this) {
            if (key < node.value) {
                node.left = insertUtil(node.left, key);
            } else if (key > node.value) {
                node.right = insertUtil(node.right, key);
            }
        }

        return node;
    }

    public void delete(int key) {
        synchronized (this) {
            root = deleteUtil(root, key);
        }
    }

    private TreeNode deleteUtil(TreeNode root, int key) {
        if (root == null)
            return root;

        synchronized (this) {
            if (key < root.value) {
                root.left = deleteUtil(root.left, key);
            } else if (key > root.value) {
                root.right = deleteUtil(root.right, key);
            } else {
                if (root.left == null)
                    return root.right;
                else if (root.right == null)
                    return root.left;

                root.value = minValue(root.right);
                root.right = deleteUtil(root.right, root.value);
            }
        }

        return root;
    }

    private int minValue(TreeNode node) {
        int minv = node.value;
        while (node.left != null) {
            minv = node.left.value;
            node = node.left;
        }
        return minv;
    }

    public void inOrder() {
        synchronized (this) {
            inOrderUtil(root);
        }
    }

    private void inOrderUtil(TreeNode root) {
        if (root != null) {
            synchronized (this) {
                inOrderUtil(root.left);
                System.out.print(root.value + " ");
                inOrderUtil(root.right);
            }
        }
    }
}
