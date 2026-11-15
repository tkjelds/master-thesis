interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> parent;
        Node<E> left;
        Node<E> right;
        boolean red = true;

        Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
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

            // The int-valued size limits the set to Integer.MAX_VALUE elements.
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree is full");
            }

            Node<T> inserted = new Node<T>(item, parent);
            if (parent == null) {
                root = inserted;
            } else if (comparison < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }

            count++;
            repairInsertion(inserted);
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

            count--;
            if (!removedWasRed) {
                repairDeletion(replacement, replacementParent);
            }
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

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
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

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == null) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
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

    private void repairInsertion(Node<T> node) {
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

    private void repairDeletion(Node<T> node, Node<T> parent) {
        while (node != root && !isRed(node)) {
            if (node == parent.left) {
                Node<T> sibling = parent.right;
                if (isRed(sibling)) {
                    sibling.red = false;
                    parent.red = true;
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (!isRed(leftOf(sibling)) && !isRed(rightOf(sibling))) {
                    setRed(sibling, true);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (!isRed(rightOf(sibling))) {
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

                if (!isRed(rightOf(sibling)) && !isRed(leftOf(sibling))) {
                    setRed(sibling, true);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (!isRed(leftOf(sibling))) {
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
