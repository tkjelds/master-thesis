interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private final class Node {
        final T item;
        Node left;
        Node right;
        Node parent;
        boolean red = true;

        Node(T item, Node parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node root;
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
            fixAfterInsertion(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node target = find(item);
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
        synchronized (lock) {
            return find(item) != null;
        }
    }

    // Empty-tree operations fail immediately; they never wait for an element.
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
            Node current = root;
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
            fillInOrder(root, result, 0);
            return result;
        }
    }

    private int fillInOrder(Node node, Object[] result, int index) {
        if (node == null) {
            return index;
        }
        index = fillInOrder(node.left, result, index);
        result[index++] = node.item;
        return fillInOrder(node.right, result, index);
    }

    private Node find(T item) {
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

    private void setRed(Node node, boolean red) {
        if (node != null) {
            node.red = red;
        }
    }

    private void rotateLeft(Node node) {
        Node right = node.right;
        node.right = right.left;
        if (right.left != null) {
            right.left.parent = node;
        }
        right.parent = node.parent;
        if (node.parent == null) {
            root = right;
        } else if (node == node.parent.left) {
            node.parent.left = right;
        } else {
            node.parent.right = right;
        }
        right.left = node;
        node.parent = right;
    }

    private void rotateRight(Node node) {
        Node left = node.left;
        node.left = left.right;
        if (left.right != null) {
            left.right.parent = node;
        }
        left.parent = node.parent;
        if (node.parent == null) {
            root = left;
        } else if (node == node.parent.left) {
            node.parent.left = left;
        } else {
            node.parent.right = left;
        }
        left.right = node;
        node.parent = left;
    }

    private void fixAfterInsertion(Node node) {
        while (node.parent != null && node.parent.red) {
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

    private void transplant(Node oldNode, Node replacement) {
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

    private void deleteNode(Node target) {
        Node removed = target;
        boolean removedWasRed = removed.red;
        Node replacement;
        Node replacementParent;

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

        if (!removedWasRed) {
            fixAfterDeletion(replacement, replacementParent);
        }
    }

    // Parent is supplied separately because node may be a null leaf.
    private void fixAfterDeletion(Node node, Node parent) {
        while (node != root && isBlack(node)) {
            if (node == parent.left) {
                Node sibling = parent.right;
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
                Node sibling = parent.left;
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
}
