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
        boolean red = true;

        Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Object monitor = new Object();
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
        synchronized (monitor) {
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

            repairAfterInsertion(inserted);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            Node<T> target = find(item);
            if (target == null) {
                return false;
            }

            deleteNode(target);
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            return find(item) != null;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node<T> current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.item;
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
            copyInOrder(root, result, 0);
            return result;
        }
    }

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == null) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
    }

    private Node<T> find(T item) {
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
        } else if (node == node.parent.left) {
            node.parent.left = pivot;
        } else {
            node.parent.right = pivot;
        }

        pivot.right = node;
        node.parent = pivot;
    }

    private void repairAfterInsertion(Node<T> node) {
        while (node.parent != null && node.parent.red) {
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
                    }
                    node.parent.red = false;
                    node.parent.parent.red = true;
                    rotateRight(node.parent.parent);
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
                    }
                    node.parent.red = false;
                    node.parent.parent.red = true;
                    rotateLeft(node.parent.parent);
                }
            }
        }
        root.red = false;
    }

    private void transplant(Node<T> oldNode, Node<T> replacement) {
        if (oldNode.parent == null) {
            root = replacement;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = replacement;
        } else {
            oldNode.parent.right = replacement;
        }
        if (replacement != null) {
            replacement.parent = oldNode.parent;
        }
    }

    private void deleteNode(Node<T> target) {
        Node<T> removed = target;
        boolean removedWasRed = removed.red;
        Node<T> replacement;
        Node<T> replacementParent;

        if (target.left == null) {
            replacement = target.right;
            replacementParent = target.parent;
            transplant(target, target.right);
        } else if (target.right == null) {
            replacement = target.left;
            replacementParent = target.parent;
            transplant(target, target.left);
        } else {
            removed = minimum(target.right);
            removedWasRed = removed.red;
            replacement = removed.right;

            if (removed.parent == target) {
                replacementParent = removed;
                if (replacement != null) {
                    replacement.parent = removed;
                }
            } else {
                replacementParent = removed.parent;
                transplant(removed, removed.right);
                removed.right = target.right;
                removed.right.parent = removed;
            }

            transplant(target, removed);
            removed.left = target.left;
            removed.left.parent = removed;
            removed.red = target.red;
        }

        if (!removedWasRed) {
            repairAfterDeletion(replacement, replacementParent);
        }
    }

    private void repairAfterDeletion(Node<T> node, Node<T> parent) {
        while (node != root && isBlack(node)) {
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
