interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E item;
        Node<E> left;
        Node<E> right;
        Node<E> parent;
        boolean red;

        Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
            this.red = true;
        }
    }

    private final Order<T> order;
    private Node<T> root;
    private int count;

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

        synchronized (this) {
            Node<T> parent = null;
            Node<T> current = root;
            int comparison = 0;

            while (current != null) {
                parent = current;
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node<T> added = new Node<T>(item, parent);
            if (parent == null) {
                root = added;
            } else if (comparison < 0) {
                parent.left = added;
            } else {
                parent.right = added;
            }

            fixAfterInsertion(added);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (this) {
            Node<T> node = findNode(item);
            if (node == null) {
                return false;
            }

            Node<T> moved = node;
            boolean movedWasRed = moved.red;
            Node<T> replacement;
            Node<T> replacementParent;

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
                    replacementParent = moved.parent;
                    transplant(moved, moved.right);
                    moved.right = node.right;
                    moved.right.parent = moved;
                }

                transplant(node, moved);
                moved.left = node.left;
                moved.left.parent = moved;
                moved.red = node.red;
            }

            if (!movedWasRed) {
                fixAfterDeletion(replacement, replacementParent);
            }

            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (this) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException();
            }
            Node<T> node = root;
            while (node.right != null) {
                node = node.right;
            }
            return node.item;
        }
    }

    public int size() {
        synchronized (this) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[count];
            int[] index = new int[] { 0 };
            copyInOrder(root, result, index);
            return result;
        }
    }

    private Node<T> findNode(T item) {
        Node<T> current = root;
        while (current != null) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return null;
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private void copyInOrder(Node<T> node, Object[] result, int[] index) {
        if (node != null) {
            copyInOrder(node.left, result, index);
            result[index[0]++] = node.item;
            copyInOrder(node.right, result, index);
        }
    }

    private void fixAfterInsertion(Node<T> node) {
        while (node != root && isRed(node.parent)) {
            Node<T> parent = node.parent;
            Node<T> grandparent = parent.parent;

            if (parent == grandparent.left) {
                Node<T> uncle = grandparent.right;
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
                Node<T> uncle = grandparent.left;
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

    private void fixAfterDeletion(Node<T> node, Node<T> parent) {
        while (node != root && isBlack(node)) {
            if (parent == null) {
                break;
            }

            if (node == parent.left) {
                Node<T> sibling = parent.right;

                if (isRed(sibling)) {
                    sibling.red = false;
                    parent.red = true;
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (isBlack(leftOf(sibling)) && isBlack(rightOf(sibling))) {
                    setRed(sibling, true);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(rightOf(sibling))) {
                        setRed(leftOf(sibling), false);
                        setRed(sibling, true);
                        rotateRight(sibling);
                        sibling = parent.right;
                    }

                    setRed(sibling, parent.red);
                    parent.red = false;
                    setRed(rightOf(sibling), false);
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node<T> sibling = parent.left;

                if (isRed(sibling)) {
                    sibling.red = false;
                    parent.red = true;
                    rotateRight(parent);
                    sibling = parent.left;
                }

                if (isBlack(rightOf(sibling)) && isBlack(leftOf(sibling))) {
                    setRed(sibling, true);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(leftOf(sibling))) {
                        setRed(rightOf(sibling), false);
                        setRed(sibling, true);
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }

                    setRed(sibling, parent.red);
                    parent.red = false;
                    setRed(leftOf(sibling), false);
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }

        setRed(node, false);
    }

    private void transplant(Node<T> oldNode, Node<T> newNode) {
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

    private void rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
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

    private void rotateRight(Node<T> node) {
        Node<T> pivot = node.left;
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

    private boolean isRed(Node<T> node) {
        return node != null && node.red;
    }

    private boolean isBlack(Node<T> node) {
        return node == null || !node.red;
    }

    private Node<T> leftOf(Node<T> node) {
        return node == null ? null : node.left;
    }

    private Node<T> rightOf(Node<T> node) {
        return node == null ? null : node.right;
    }

    private void setRed(Node<T> node, boolean red) {
        if (node != null) {
            node.red = red;
        }
    }
}
