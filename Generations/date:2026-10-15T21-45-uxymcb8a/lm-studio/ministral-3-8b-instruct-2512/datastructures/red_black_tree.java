@RedBlackTree.ThreadSafe
class RedBlackTree {
    @interface ThreadSafe {}

    private static final boolean RED = false;
    private static final boolean BLACK = true;

    private Node root;
    private volatile int size;

    private static class Node {
        int key, value;
        boolean color; // RED or BLACK
        Node left, right, parent;

        Node(int key, int value) {
            this.key = key;
            this.value = value;
            this.color = RED; // New nodes are always red
        }
    }

    public RedBlackTree() {
        root = null;
        size = 0;
    }

    // Public thread-safe operations
    public synchronized void put(int key, int value) {
        if (root == null) {
            root = new Node(key, value);
            root.color = BLACK;
            size++;
        } else {
            putHelper(root, key, value);
        }
    }

    public synchronized int get(int key) {
        Node node = getHelper(root, key);
        return (node != null) ? node.value : -1; // Return -1 if not found
    }

    public synchronized void remove(int key) {
        Node node = getHelper(root, key);
        if (node != null) {
            removeNode(node);
        }
    }

    public synchronized int size() {
        return size;
    }

    // Helper methods
    private void putHelper(Node node, int key, int value) {
        Node newNode = new Node(key, value);
        Node parent = null;
        Node current = root;

        while (current != null) {
            parent = current;
            if (key < current.key) {
                current = current.left;
            } else if (key > current.key) {
                current = current.right;
            } else {
                current.value = value; // Update existing key
                return;
            }
        }

        newNode.parent = parent;
        if (key < parent.key) {
            parent.left = newNode;
        } else {
            parent.right = newNode;
        }

        fixInsert(newNode);
    }

    private Node getHelper(Node node, int key) {
        while (node != null) {
            if (key == node.key) {
                return node;
            } else if (key < node.key) {
                node = node.left;
            } else {
                node = node.right;
            }
        }
        return null;
    }

    private void removeNode(Node node) {
        Node child, parent;

        if (node.left == null || node.right == null) {
            // Case 1: Node has at most one child
            parent = node.parent;
            if (node.left == null) {
                child = node.right;
            } else {
                child = node.left;
            }

            if (child != null) {
                child.parent = parent;
            }

            if (parent == null) {
                root = child;
            } else if (node == parent.left) {
                parent.left = child;
            } else {
                parent.right = child;
            }

            if (node.color == BLACK) {
                fixDelete(child);
            }
        } else {
            // Case 2: Node has two children
            Node successor = node.right;
            while (successor.left != null) {
                successor = successor.left;
            }

            node.key = successor.key;
            node.value = successor.value;

            removeNode(successor);
        }
    }

    private void fixInsert(Node node) {
        Node parent, uncle;

        while (node != root && node.color == RED && node.parent.color == RED) {
            parent = node.parent;
            if (parent == parent.parent.left) {
                uncle = parent.parent.right;

                if (uncle != null && uncle.color == RED) {
                    // Case 1: Uncle is red
                    parent.color = BLACK;
                    uncle.color = BLACK;
                    parent.parent.color = RED;
                    node = parent.parent;
                } else {
                    // Case 2: Uncle is black or null
                    if (node == parent.right) {
                        // Case 2a: Node is right child
                        rotateLeft(parent);
                        node = parent;
                        parent = node.parent;
                    }

                    // Case 2b: Node is left child
                    rotateRight(parent.parent);
                    if (parent.parent.color == RED) {
                        parent.parent.color = BLACK;
                    }
                    parent.color = RED;
                    node = root; // Stop recursion
                }
            } else {
                uncle = parent.parent.left;

                if (uncle != null && uncle.color == RED) {
                    // Case 1: Uncle is red
                    parent.color = BLACK;
                    uncle.color = BLACK;
                    parent.parent.color = RED;
                    node = parent.parent;
                } else {
                    // Case 2: Uncle is black or null
                    if (node == parent.left) {
                        // Case 2a: Node is left child
                        rotateRight(parent);
                        node = parent;
                        parent = node.parent;
                    }

                    // Case 2b: Node is right child
                    rotateLeft(parent.parent);
                    if (parent.parent.color == RED) {
                        parent.parent.color = BLACK;
                    }
                    parent.color = RED;
                    node = root; // Stop recursion
                }
            }
        }

        root.color = BLACK;
    }

    private void fixDelete(Node node) {
        Node sibling;

        while (node != root && (node == null || node.color == BLACK)) {
            if (node == node.parent.left) {
                sibling = node.parent.right;

                if (sibling != null && sibling.color == RED) {
                    // Case 1: Sibling is red
                    sibling.color = BLACK;
                    node.parent.color = RED;
                    rotateLeft(node.parent);
                }

                if ((sibling.left != null && sibling.left.color == BLACK) &&
                    (sibling.right != null && sibling.right.color == BLACK)) {
                    // Case 2: Both children of sibling are black
                    sibling.color = RED;
                    node = node.parent;
                } else {
                    if (sibling.right == null || sibling.right.color == BLACK) {
                        // Case 3: Sibling's right child is black
                        if (sibling.left != null && sibling.left.color == BLACK) {
                            sibling.left.color = RED;
                            sibling.color = RED;
                            rotateRight(sibling);
                            sibling = node.parent.right;
                        }

                        // Case 4: Sibling's right child is red
                        sibling.color = node.parent.color;
                        node.parent.color = BLACK;
                        sibling.right.color = BLACK;
                        rotateLeft(node.parent);
                        node = root; // Stop recursion
                    } else {
                        // Case 5: Sibling's right child is red
                        sibling.right.color = BLACK;
                        rotateRight(sibling);
                        sibling = node.parent.right;
                    }
                }
            } else {
                // Mirror cases for right child
                sibling = node.parent.left;

                if (sibling != null && sibling.color == RED) {
                    // Case 1: Sibling is red
                    sibling.color = BLACK;
                    node.parent.color = RED;
                    rotateRight(node.parent);
                }

                if ((sibling.left != null && sibling.left.color == BLACK) &&
                    (sibling.right != null && sibling.right.color == BLACK)) {
                    // Case 2: Both children of sibling are black
                    sibling.color = RED;
                    node = node.parent;
                } else {
                    if (sibling.left == null || sibling.left.color == BLACK) {
                        // Case 3: Sibling's left child is black
                        if (sibling.right != null && sibling.right.color == BLACK) {
                            sibling.right.color = RED;
                            sibling.color = RED;
                            rotateLeft(sibling);
                            sibling = node.parent.left;
                        }

                        // Case 4: Sibling's left child is red
                        sibling.color = node.parent.color;
                        node.parent.color = BLACK;
                        sibling.left.color = BLACK;
                        rotateLeft(node.parent);
                        node = root; // Stop recursion
                    } else {
                        // Case 5: Sibling's left child is red
                        sibling.left.color = BLACK;
                        rotateRight(sibling);
                        sibling = node.parent.left;
                    }
                }
            }

            if (node != null && node.color == BLACK) {
                break; // Stop recursion
            }
        }

        if (node != null) {
            node.color = BLACK;
        }
    }

    private void rotateLeft(Node node) {
        Node rightChild = node.right;
        node.right = rightChild.left;

        if (rightChild.left != null) {
            rightChild.left.parent = node;
        }

        rightChild.parent = node.parent;

        if (node.parent == null) {
            root = rightChild;
        } else if (node == node.parent.left) {
            node.parent.left = rightChild;
        } else {
            node.parent.right = rightChild;
        }

        rightChild.left = node;
        node.parent = rightChild;
    }

    private void rotateRight(Node node) {
        Node leftChild = node.left;
        node.left = leftChild.right;

        if (leftChild.right != null) {
            leftChild.right.parent = node;
        }

        leftChild.parent = node.parent;

        if (node.parent == null) {
            root = leftChild;
        } else if (node == node.parent.right) {
            node.parent.right = leftChild;
        } of (node == node.parent.left) {
            node.parent.left = leftChild;
        }

        leftChild.right = node;
        node.parent = leftChild;
    }
}
