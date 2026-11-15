interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private static final class Node<E> {
        E item;
        Node<E> left;
        Node<E> right;
        Node<E> parent;
        boolean red = RED;

        Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int count;

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

        synchronized (lock) {
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

            Node<T> inserted = new Node<T>(item, parent);
            if (parent == null) {
                root = inserted;
            } else if (comparison < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }

            fixAfterInsertion(inserted);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Node<T> node = findNode(item);
            if (node == null) {
                return false;
            }
            deleteNode(node);
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return maximum(root).item;
        }
    }

    public int size() {
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            Node<T> node = root == null ? null : minimum(root);

            for (int i = 0; i < result.length; i++) {
                result[i] = node.item;
                node = successor(node);
            }
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

    private Node<T> maximum(Node<T> node) {
        while (node.right != null) {
            node = node.right;
        }
        return node;
    }

    private Node<T> successor(Node<T> node) {
        if (node.right != null) {
            return minimum(node.right);
        }

        Node<T> parent = node.parent;
        while (parent != null && node == parent.right) {
            node = parent;
            parent = parent.parent;
        }
        return parent;
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

    private void fixAfterInsertion(Node<T> node) {
        while (node != root && isRed(node.parent)) {
            Node<T> parent = node.parent;
            Node<T> grandparent = parent.parent;

            if (parent == grandparent.left) {
                Node<T> uncle = grandparent.right;
                if (isRed(uncle)) {
                    parent.red = BLACK;
                    uncle.red = BLACK;
                    grandparent.red = RED;
                    node = grandparent;
                } else {
                    if (node == parent.right) {
                        node = parent;
                        rotateLeft(node);
                        parent = node.parent;
                        grandparent = parent.parent;
                    }
                    parent.red = BLACK;
                    grandparent.red = RED;
                    rotateRight(grandparent);
                }
            } else {
                Node<T> uncle = grandparent.left;
                if (isRed(uncle)) {
                    parent.red = BLACK;
                    uncle.red = BLACK;
                    grandparent.red = RED;
                    node = grandparent;
                } else {
                    if (node == parent.left) {
                        node = parent;
                        rotateRight(node);
                        parent = node.parent;
                        grandparent = parent.parent;
                    }
                    parent.red = BLACK;
                    grandparent.red = RED;
                    rotateLeft(grandparent);
                }
            }
        }
        root.red = BLACK;
    }

    private void deleteNode(Node<T> node) {
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

            if (replacement != null) {
                replacementParent = replacement.parent;
            }
        }

        if (!movedWasRed) {
            fixAfterDeletion(replacement, replacementParent);
        }

        node.left = null;
        node.right = null;
        node.parent = null;
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

    private void fixAfterDeletion(Node<T> node, Node<T> parent) {
        while (node != root && isBlack(node)) {
            if (parent == null) {
                break;
            }

            if (node == parent.left) {
                Node<T> sibling = parent.right;

                if (isRed(sibling)) {
                    sibling.red = BLACK;
                    parent.red = RED;
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (isBlack(leftOf(sibling)) && isBlack(rightOf(sibling))) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(rightOf(sibling))) {
                        setColor(leftOf(sibling), BLACK);
                        setColor(sibling, RED);
                        if (sibling != null) {
                            rotateRight(sibling);
                        }
                        sibling = parent.right;
                    }

                    setColor(sibling, parent.red);
                    parent.red = BLACK;
                    setColor(rightOf(sibling), BLACK);
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node<T> sibling = parent.left;

                if (isRed(sibling)) {
                    sibling.red = BLACK;
                    parent.red = RED;
                    rotateRight(parent);
                    sibling = parent.left;
                }

                if (isBlack(rightOf(sibling)) && isBlack(leftOf(sibling))) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(leftOf(sibling))) {
                        setColor(rightOf(sibling), BLACK);
                        setColor(sibling, RED);
                        if (sibling != null) {
                            rotateLeft(sibling);
                        }
                        sibling = parent.left;
                    }

                    setColor(sibling, parent.red);
                    parent.red = BLACK;
                    setColor(leftOf(sibling), BLACK);
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }

        setColor(node, BLACK);
    }

    private boolean isRed(Node<T> node) {
        return node != null && node.red == RED;
    }

    private boolean isBlack(Node<T> node) {
        return node == null || node.red == BLACK;
    }

    private Node<T> leftOf(Node<T> node) {
        return node == null ? null : node.left;
    }

    private Node<T> rightOf(Node<T> node) {
        return node == null ? null : node.right;
    }

    private void setColor(Node<T> node, boolean color) {
        if (node != null) {
            node.red = color;
        }
    }
}
