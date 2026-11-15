interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node root;
    private int count;

    private final class Node {
        T item;
        Node left;
        Node right;
        Node parent;
        boolean red;

        Node(T item, Node parent) {
            this.item = item;
            this.parent = parent;
            this.red = true;
        }
    }

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            Node parent = null;
            Node current = root;
            int comparison = 0;

            while (current != null) {
                parent = current;
                comparison = order.compare(item, current.item);
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

            count++;
            repairAfterInsertion(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            Node target = findNode(item);
            if (target == null) {
                return false;
            }

            Node moved = target;
            boolean movedWasRed = moved.red;
            Node replacement;
            Node replacementParent;

            if (target.left == null) {
                replacement = target.right;
                replacementParent = target.parent;
                transplant(target, target.right);
                if (replacement != null) {
                    replacementParent = replacement.parent;
                }
            } else if (target.right == null) {
                replacement = target.left;
                replacementParent = target.parent;
                transplant(target, target.left);
                if (replacement != null) {
                    replacementParent = replacement.parent;
                }
            } else {
                moved = minimum(target.right);
                movedWasRed = moved.red;
                replacement = moved.right;

                if (moved.parent == target) {
                    replacementParent = moved;
                    if (replacement != null) {
                        replacement.parent = moved;
                    }
                } else {
                    Node oldParent = moved.parent;
                    transplant(moved, moved.right);
                    replacementParent = replacement != null
                            ? replacement.parent
                            : oldParent;
                    moved.right = target.right;
                    moved.right.parent = moved;
                }

                transplant(target, moved);
                moved.left = target.left;
                moved.left.parent = moved;
                moved.red = target.red;
            }

            count--;
            if (!movedWasRed) {
                repairAfterDeletion(replacement, replacementParent);
            }

            target.left = null;
            target.right = null;
            target.parent = null;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return maximum(root).item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[count];
            Node current = root == null ? null : minimum(root);

            for (int i = 0; i < result.length; i++) {
                result[i] = current.item;
                current = successor(current);
            }
            return result;
        }
    }

    private Node findNode(T item) {
        Node current = root;
        while (current != null) {
            int comparison = order.compare(item, current.item);
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

    private Node successor(Node node) {
        if (node.right != null) {
            return minimum(node.right);
        }

        Node parent = node.parent;
        while (parent != null && node == parent.right) {
            node = parent;
            parent = parent.parent;
        }
        return parent;
    }

    private void repairAfterInsertion(Node node) {
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

        if (root != null) {
            root.red = false;
        }
    }

    private void repairAfterDeletion(Node node, Node parent) {
        while (node != root && isBlack(node)) {
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

                if (isBlack(leftOf(sibling)) && isBlack(rightOf(sibling))) {
                    if (sibling != null) {
                        sibling.red = true;
                    }
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(rightOf(sibling))) {
                        Node nearChild = leftOf(sibling);
                        if (nearChild != null) {
                            nearChild.red = false;
                        }
                        if (sibling != null) {
                            sibling.red = true;
                            rotateRight(sibling);
                        }
                        sibling = parent.right;
                    }

                    if (sibling != null) {
                        sibling.red = parent.red;
                    }
                    parent.red = false;
                    Node farChild = rightOf(sibling);
                    if (farChild != null) {
                        farChild.red = false;
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

                if (isBlack(rightOf(sibling)) && isBlack(leftOf(sibling))) {
                    if (sibling != null) {
                        sibling.red = true;
                    }
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(leftOf(sibling))) {
                        Node nearChild = rightOf(sibling);
                        if (nearChild != null) {
                            nearChild.red = false;
                        }
                        if (sibling != null) {
                            sibling.red = true;
                            rotateLeft(sibling);
                        }
                        sibling = parent.left;
                    }

                    if (sibling != null) {
                        sibling.red = parent.red;
                    }
                    parent.red = false;
                    Node farChild = leftOf(sibling);
                    if (farChild != null) {
                        farChild.red = false;
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

    private boolean isRed(Node node) {
        return node != null && node.red;
    }

    private boolean isBlack(Node node) {
        return node == null || !node.red;
    }

    private Node leftOf(Node node) {
        return node == null ? null : node.left;
    }

    private Node rightOf(Node node) {
        return node == null ? null : node.right;
    }
}
