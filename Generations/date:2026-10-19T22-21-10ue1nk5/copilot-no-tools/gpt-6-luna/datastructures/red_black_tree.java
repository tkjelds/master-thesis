@red_black_tree.ThreadSafe
public final class red_black_tree {
    @interface ThreadSafe {}

    private static final class Node {
        int key;
        boolean black;
        Node left;
        Node right;
        Node parent;

        Node(int key, boolean black) {
            this.key = key;
            this.black = black;
        }
    }

    private final Object monitor = new Object();
    private final Node nil = new Node(0, true);
    private Node root = nil;
    private int size;

    public red_black_tree() {
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
    }

    public boolean insert(int key) {
        synchronized (monitor) {
            Node parent = nil;
            Node current = root;

            while (current != nil) {
                parent = current;
                if (key == current.key) {
                    return false;
                }
                current = key < current.key ? current.left : current.right;
            }

            Node node = new Node(key, false);
            node.left = nil;
            node.right = nil;
            node.parent = parent;

            if (parent == nil) {
                root = node;
            } else if (key < parent.key) {
                parent.left = node;
            } else {
                parent.right = node;
            }

            insertFixup(node);
            size++;
            return true;
        }
    }

    public boolean add(int key) {
        return insert(key);
    }

    public boolean delete(int key) {
        synchronized (monitor) {
            Node target = findNode(key);
            if (target == nil) {
                return false;
            }

            Node moved = target;
            boolean movedWasBlack = moved.black;
            Node replacement;

            if (target.left == nil) {
                replacement = target.right;
                transplant(target, target.right);
            } else if (target.right == nil) {
                replacement = target.left;
                transplant(target, target.left);
            } else {
                moved = minimumNode(target.right);
                movedWasBlack = moved.black;
                replacement = moved.right;

                if (moved.parent == target) {
                    replacement.parent = moved;
                } else {
                    transplant(moved, moved.right);
                    moved.right = target.right;
                    moved.right.parent = moved;
                }

                transplant(target, moved);
                moved.left = target.left;
                moved.left.parent = moved;
                moved.black = target.black;
            }

            size--;
            if (movedWasBlack) {
                deleteFixup(replacement);
            }
            nil.parent = nil;
            return true;
        }
    }

    public boolean remove(int key) {
        return delete(key);
    }

    public boolean contains(int key) {
        synchronized (monitor) {
            return findNode(key) != nil;
        }
    }

    public boolean search(int key) {
        return contains(key);
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public int minimum() {
        synchronized (monitor) {
            if (root == nil) {
                throw new IllegalStateException("Tree is empty");
            }
            return minimumNode(root).key;
        }
    }

    public int maximum() {
        synchronized (monitor) {
            if (root == nil) {
                throw new IllegalStateException("Tree is empty");
            }
            Node current = root;
            while (current.right != nil) {
                current = current.right;
            }
            return current.key;
        }
    }

    public int[] values() {
        synchronized (monitor) {
            int[] result = new int[size];
            fillInOrder(root, result, new int[] {0});
            return result;
        }
    }

    public void clear() {
        synchronized (monitor) {
            root = nil;
            size = 0;
            nil.parent = nil;
        }
    }

    private Node findNode(int key) {
        Node current = root;
        while (current != nil && current.key != key) {
            current = key < current.key ? current.left : current.right;
        }
        return current;
    }

    private Node minimumNode(Node node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private void rotateLeft(Node node) {
        Node pivot = node.right;
        node.right = pivot.left;
        if (pivot.left != nil) {
            pivot.left.parent = node;
        }

        pivot.parent = node.parent;
        if (node.parent == nil) {
            root = pivot;
        } else if (node == node.parent.left) {
            node.parent.left = pivot;
        } else {
            node.parent.right = pivot;
        }

        pivot.left = node;
        node.parent = pivot;
    }

    private void rotateRight(Node node) {
        Node pivot = node.left;
        node.left = pivot.right;
        if (pivot.right != nil) {
            pivot.right.parent = node;
        }

        pivot.parent = node.parent;
        if (node.parent == nil) {
            root = pivot;
        } else if (node == node.parent.right) {
            node.parent.right = pivot;
        } else {
            node.parent.left = pivot;
        }

        pivot.right = node;
        node.parent = pivot;
    }

    private void insertFixup(Node node) {
        while (!node.parent.black) {
            if (node.parent == node.parent.parent.left) {
                Node uncle = node.parent.parent.right;
                if (!uncle.black) {
                    node.parent.black = true;
                    uncle.black = true;
                    node.parent.parent.black = false;
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.right) {
                        node = node.parent;
                        rotateLeft(node);
                    }
                    node.parent.black = true;
                    node.parent.parent.black = false;
                    rotateRight(node.parent.parent);
                }
            } else {
                Node uncle = node.parent.parent.left;
                if (!uncle.black) {
                    node.parent.black = true;
                    uncle.black = true;
                    node.parent.parent.black = false;
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.left) {
                        node = node.parent;
                        rotateRight(node);
                    }
                    node.parent.black = true;
                    node.parent.parent.black = false;
                    rotateLeft(node.parent.parent);
                }
            }
        }
        root.black = true;
        root.parent = nil;
    }

    private void transplant(Node oldNode, Node newNode) {
        if (oldNode.parent == nil) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        newNode.parent = oldNode.parent;
    }

    private void deleteFixup(Node node) {
        while (node != root && node.black) {
            if (node == node.parent.left) {
                Node sibling = node.parent.right;
                if (!sibling.black) {
                    sibling.black = true;
                    node.parent.black = false;
                    rotateLeft(node.parent);
                    sibling = node.parent.right;
                }

                if (sibling.left.black && sibling.right.black) {
                    if (sibling != nil) {
                        sibling.black = false;
                    }
                    node = node.parent;
                } else {
                    if (sibling.right.black) {
                        sibling.left.black = true;
                        if (sibling != nil) {
                            sibling.black = false;
                        }
                        rotateRight(sibling);
                        sibling = node.parent.right;
                    }

                    sibling.black = node.parent.black;
                    node.parent.black = true;
                    sibling.right.black = true;
                    rotateLeft(node.parent);
                    node = root;
                }
            } else {
                Node sibling = node.parent.left;
                if (!sibling.black) {
                    sibling.black = true;
                    node.parent.black = false;
                    rotateRight(node.parent);
                    sibling = node.parent.left;
                }

                if (sibling.right.black && sibling.left.black) {
                    if (sibling != nil) {
                        sibling.black = false;
                    }
                    node = node.parent;
                } else {
                    if (sibling.left.black) {
                        sibling.right.black = true;
                        if (sibling != nil) {
                            sibling.black = false;
                        }
                        rotateLeft(sibling);
                        sibling = node.parent.left;
                    }

                    sibling.black = node.parent.black;
                    node.parent.black = true;
                    sibling.left.black = true;
                    rotateRight(node.parent);
                    node = root;
                }
            }
        }
        node.black = true;
    }

    private void fillInOrder(Node node, int[] result, int[] index) {
        if (node == nil) {
            return;
        }
        fillInOrder(node.left, result, index);
        result[index[0]++] = node.key;
        fillInOrder(node.right, result, index);
    }
}
