interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Object lock = new Object();
    private Node root;
    private int size;

    private final class Node {
        T value;
        Node left;
        Node right;
        Node parent;
        boolean red;

        Node(T value, Node parent) {
            this.value = value;
            this.parent = parent;
            this.red = true;
        }
    }

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node parent = null;
            Node current = root;
            int comparison = 0;

            while (current != null) {
                parent = current;
                comparison = order.compare(item, current.value);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node inserted = new Node(item, parent);
            if (parent == null) {
                root = inserted;
            } else if (comparison < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }

            size++;
            fixAfterInsertion(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node node = findNode(item);
            if (node == null) {
                return false;
            }

            deleteNode(node);
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return minimum(root).value;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return maximum(root).value;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            copyInOrder(root, result, 0);
            return result;
        }
    }

    private Node findNode(T item) {
        Node current = root;
        while (current != null) {
            int comparison = order.compare(item, current.value);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private Node minimum(Node node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node maximum(Node node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    private int copyInOrder(Node node, Object[] result, int index) {
        if (node == null) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.value;
        return copyInOrder(node.right, result, index);
    }

    private void rotateLeft(Node node) {
        Node pivot = node.right;
        node.right = pivot.left;
        if (pivot.left != null) {
            pivot.left.parent = node;
        }

        pivot.parent = node.parent;
        if (node.parent == null) {
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
        if (pivot.right != null) {
            pivot.right.parent = node;
        }

        pivot.parent = node.parent;
        if (node.parent == null) {
            root = pivot;
        } else if (node == node.parent.right) {
            node.parent.right = pivot;
        } else {
            node.parent.left = pivot;
        }

        pivot.right = node;
        node.parent = pivot;
    }

    private void fixAfterInsertion(Node node) {
        while (node != root && isRed(node.parent)) {
            Node parent = node.parent;
            Node grandparent = parent.parent;

            if (parent == grandparent.left) {
                Node uncle = grandparent.right;
                if (isRed(uncle)) {
                    parent.red = false;
                    uncle.red = false;
                    grandparent.red = true;
                    node = grandparent;
                } else {
                    if (node == parent.right) {
                        node = parent;
                        rotateLeft(node);
                        parent = node.parent;
                        grandparent = parent.parent;
                    }
                    parent.red = false;
                    grandparent.red = true;
                    rotateRight(grandparent);
                }
            } else {
                Node uncle = grandparent.left;
                if (isRed(uncle)) {
                    parent.red = false;
                    uncle.red = false;
                    grandparent.red = true;
                    node = grandparent;
                } else {
                    if (node == parent.left) {
                        node = parent;
                        rotateRight(node);
                        parent = node.parent;
                        grandparent = parent.parent;
                    }
                    parent.red = false;
                    grandparent.red = true;
                    rotateLeft(grandparent);
                }
            }
        }
        root.red = false;
    }

    private void deleteNode(Node node) {
        Node moved = node;
        boolean movedWasRed = moved.red;
        Node replacement;
        Node replacementParent;

        if (node.left == null) {
            replacement = node.right;
            replacementParent = node.parent;
            transplant(node, node.right);
            if (replacement != null) {
                replacementParent = replacement.parent;
            }
        } else if (node.right == null) {
            replacement = node.left;
            replacementParent = node.parent;
            transplant(node, node.left);
            if (replacement != null) {
                replacementParent = replacement.parent;
            }
        } else {
            moved = minimum(node.right);
            movedWasRed = moved.red;
            replacement = moved.right;

            if (moved.parent == node) {
                replacementParent = moved;
                if (replacement != null) {
                    replacement.parent = moved;
                }
            } else {
                Node oldParent = moved.parent;
                transplant(moved, moved.right);
                replacementParent = replacement != null ? replacement.parent : oldParent;
                moved.right = node.right;
                moved.right.parent = moved;
            }

            transplant(node, moved);
            moved.left = node.left;
            moved.left.parent = moved;
            moved.red = node.red;
        }

        if (!movedWasRed) {
            fixAfterDeletion(replacement, replacement == null ? replacementParent : replacement.parent);
        }
        if (root != null) {
            root.red = false;
        }
    }

    private void transplant(Node oldNode, Node newNode) {
        if (oldNode.parent == null) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        if (newNode != null) {
            newNode.parent = oldNode.parent;
        }
    }

    private void fixAfterDeletion(Node node, Node parent) {
        while (node != root && !isRed(node)) {
            if (parent == null) {
                break;
            }

            if (node == parent.left) {
                Node sibling = parent.right;
                if (isRed(sibling)) {
                    sibling.red = false;
                    parent.red = true;
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (sibling == null) {
                    node = parent;
                    parent = node.parent;
                    continue;
                }

                if (!isRed(sibling.left) && !isRed(sibling.right)) {
                    sibling.red = true;
                    node = parent;
                    parent = node.parent;
                } else {
                    if (!isRed(sibling.right)) {
                        if (sibling.left != null) {
                            sibling.left.red = false;
                        }
                        sibling.red = true;
                        rotateRight(sibling);
                        sibling = parent.right;
                    }

                    sibling.red = parent.red;
                    parent.red = false;
                    if (sibling.right != null) {
                        sibling.right.red = false;
                    }
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node sibling = parent.left;
                if (isRed(sibling)) {
                    sibling.red = false;
                    parent.red = true;
                    rotateRight(parent);
                    sibling = parent.left;
                }

                if (sibling == null) {
                    node = parent;
                    parent = node.parent;
                    continue;
                }

                if (!isRed(sibling.left) && !isRed(sibling.right)) {
                    sibling.red = true;
                    node = parent;
                    parent = node.parent;
                } else {
                    if (!isRed(sibling.left)) {
                        if (sibling.right != null) {
                            sibling.right.red = false;
                        }
                        sibling.red = true;
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }

                    sibling.red = parent.red;
                    parent.red = false;
                    if (sibling.left != null) {
                        sibling.left.red = false;
                    }
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }

        if (node != null) {
            node.red = false;
        }
    }

    private boolean isRed(Node node) {
        return node != null && node.red;
    }
}
