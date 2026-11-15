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

    private final Object lock = new Object();
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

            fixInsertion(inserted);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node<T> target = find(item);
            if (target == null) {
                return false;
            }

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
                fixDeletion(replacement, replacementParent);
            }
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            return find(item) != null;
        }
    }

    // Empty extremum lookups fail immediately; no operation waits for an item.
    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
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
        synchronized (lock) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
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
        Node<T> node = root;
        while (node != null) {
            int comparison = order.compare(item, node.item);
            if (comparison == 0) {
                return node;
            }
            node = comparison < 0 ? node.left : node.right;
        }
        return null;
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
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

    private void setBlack(Node<T> node) {
        if (node != null) {
            node.red = false;
        }
    }

    private void setRed(Node<T> node) {
        if (node != null) {
            node.red = true;
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
        } else if (node == node.parent.left) {
            node.parent.left = pivot;
        } else {
            node.parent.right = pivot;
        }
        pivot.right = node;
        node.parent = pivot;
    }

    private void fixInsertion(Node<T> node) {
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

    private void fixDeletion(Node<T> node, Node<T> parent) {
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
                    setRed(sibling);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(rightOf(sibling))) {
                        setBlack(leftOf(sibling));
                        setRed(sibling);
                        rotateRight(sibling);
                        sibling = parent.right;
                    }
                    sibling.red = parent.red;
                    parent.red = false;
                    setBlack(rightOf(sibling));
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
                    setRed(sibling);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (isBlack(leftOf(sibling))) {
                        setBlack(rightOf(sibling));
                        setRed(sibling);
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }
                    sibling.red = parent.red;
                    parent.red = false;
                    setBlack(leftOf(sibling));
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }
        setBlack(node);
    }
}
