@BinarySearchTree.ThreadSafe
class BinarySearchTree {
    @interface ThreadSafe {}

    private static class Node {
        int value;
        Node left, right;

        Node(int value) {
            this.value = value;
        }
    }

    private volatile Node root;
    private final Object lock = new Object();

    public BinarySearchTree() {
        this.root = null;
    }

    public void insert(int value) throws InterruptedException {
        synchronized (lock) {
            while (true) {
                Node newNode = new Node(value);
                Node parent = null;
                Node current = root;

                // Find the insertion point
                while (current != null) {
                    parent = current;
                    if (value < current.value) {
                        current = current.left;
                    } else if (value > current.value) {
                        current = current.right;
                    } else {
                        // Value already exists, no insertion needed
                        return;
                    }
                }

                // Insert the new node
                if (parent == null) {
                    root = newNode;
                } else if (value < parent.value) {
                    parent.left = newNode;
                } else {
                    parent.right = newNode;
                }
                return;
            }
        }
    }

    public boolean contains(int value) throws InterruptedException {
        synchronized (lock) {
            Node current = root;
            while (current != null) {
                if (value == current.value) {
                    return true;
                } else if (value < current.value) {
                    current = current.left;
                } else {
                    current = current.right;
                }
            }
            return false;
        }
    }

    public void delete(int value) throws InterruptedException {
        synchronized (lock) {
            Node parent = null;
            Node current = root;
            Node nodeToDelete = null;

            // Find the node to delete and its parent
            while (current != null) {
                if (value == current.value) {
                    nodeToDelete = current;
                    break;
                } else if (value < current.value) {
                    parent = current;
                    current = current.left;
                } else {
                    parent = current;
                    current = current.right;
                }
            }

            if (nodeToDelete == null) {
                return; // Value not found
            }

            // Case 1: Node has no children or one child
            if (nodeToDelete.left == null || nodeToDelete.right == null) {
                Node child = (nodeToDelete.left != null) ? nodeToDelete.left : nodeToDelete.right;
                if (parent == null) {
                    root = child;
                } else if (nodeToDelete == parent.left) {
                    parent.left = child;
                } else {
                    parent.right = child;
                }
            }
            // Case 2: Node has two children
            else {
                Node successorParent = nodeToDelete;
                Node successor = nodeToDelete.right;

                // Find the inorder successor (smallest in the right subtree)
                while (successor.left != null) {
                    successorParent = successor;
                    successor = successor.left;
                }

                // Replace nodeToDelete's value with successor's value
                nodeToDelete.value = successor.value;

                // Delete the successor (which has no left child)
                if (successorParent == nodeToDelete) {
                    successorParent.right = successor.right;
                } else {
                    successorParent.left = successor.right;
                }
            }
        }
    }

    public void traverseInOrder() throws InterruptedException {
        synchronized (lock) {
            Node current = root;
            // Use a stack to simulate recursion
            java.util.ArrayList<Integer> result = new java.util.ArrayList<>();
            java.util.Stack<Node> stack = new java.util.Stack<>();

            while (current != null || !stack.isEmpty()) {
                while (current != null) {
                    stack.push(current);
                    current = current.left;
                }
                current = stack.pop();
                result.add(current.value);
                current = current.right;
            }

            // Print the traversal (for demonstration)
            for (int val : result) {
                System.out.print(val + " ");
            }
            System.out.println();
        }
    }

    public int size() throws InterruptedException {
        synchronized (lock) {
            return countNodes(root);
        }
    }

    private int countNodes(Node node) {
        if (node == null) {
            return 0;
        }
        return 1 + countNodes(node.left) + countNodes(node.right);
    }

    public boolean isEmpty() throws InterruptedException {
        synchronized (lock) {
            return root == null;
        }
    }

    public void clear() throws InterruptedException {
        synchronized (lock) {
            root = null;
        }
    }

    public int getMin() throws InterruptedException {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.value;
        }
    }

    public int getMax() throws InterruptedException {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.value;
        }
    }

    public void printTree() throws InterruptedException {
        synchronized (lock) {
            System.out.println("Tree structure:");
            printSubtree(root, 0);
        }
    }

    private void printSubtree(Node node, int level) {
        if (node == null) return;
        printSubtree(node.right, level + 1);
        for (int i = 0;    i < level;    i++) {
            System.out.print("   ");
        }
        System.out.println(node.value);
        printSubtree(node.left, level + 1);
    }
}
