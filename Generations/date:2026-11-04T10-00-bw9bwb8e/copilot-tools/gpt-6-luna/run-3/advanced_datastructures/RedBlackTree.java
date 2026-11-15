interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Order<T> order;
    private final Object monitor = new Object();
    private Node<T> root;
    private int count;

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

        synchronized (monitor) {
            Node<T> target = findNode(item);
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
            copyInOrder(root, result, 0);
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

    private int copyInOrder(Node<T> node, Object[] output, int index) {
        if (node == null) {
            return index;
        }
        index = copyInOrder(node.left, output, index);
        output[index++] = node.item;
        return copyInOrder(node.right, output, index);
    }

    private void fixAfterInsertion(Node<T> node) {
        node.red = RED;

        while (node != root && colorOf(parentOf(node)) == RED) {
            Node<T> parent = parentOf(node);
            Node<T> grandparent = parentOf(parent);

            if (parent == leftOf(grandparent)) {
                Node<T> uncle = rightOf(grandparent);
                if (colorOf(uncle) == RED) {
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == rightOf(parent)) {
                        node = parent;
                        rotateLeft(node);
                        parent = parentOf(node);
                        grandparent = parentOf(parent);
                    }
                    setColor(parent, BLACK);
                    setColor(grandparent, RED);
                    rotateRight(grandparent);
                }
            } else {
                Node<T> uncle = leftOf(grandparent);
                if (colorOf(uncle) == RED) {
                    setColor(parent, BLACK);
                    setColor(uncle, BLACK);
                    setColor(grandparent, RED);
                    node = grandparent;
                } else {
                    if (node == leftOf(parent)) {
                        node = parent;
                        rotateRight(node);
                        parent = parentOf(node);
                        grandparent = parentOf(parent);
                    }
                    setColor(parent, BLACK);
                    setColor(grandparent, RED);
                    rotateLeft(grandparent);
                }
            }
        }

        setColor(root, BLACK);
    }

    private void deleteNode(Node<T> target) {
        Node<T> moved = target;
        boolean movedWasRed = moved.red;
        Node<T> replacement;
        Node<T> replacementParent;

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
                Node<T> oldParent = moved.parent;
                transplant(moved, moved.right);
                replacementParent = replacement != null ? replacement.parent : oldParent;
                moved.right = target.right;
                moved.right.parent = moved;
            }

            transplant(target, moved);
            moved.left = target.left;
            moved.left.parent = moved;
            moved.red = target.red;
        }

        if (movedWasRed == BLACK) {
            fixAfterDeletion(replacement, replacementParent);
        }
    }

    private void fixAfterDeletion(Node<T> node, Node<T> parent) {
        while (node != root && colorOf(node) == BLACK) {
            if (parent == null) {
                break;
            }

            if (node == leftOf(parent)) {
                Node<T> sibling = rightOf(parent);

                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateLeft(parent);
                    sibling = rightOf(parent);
                }

                if (colorOf(leftOf(sibling)) == BLACK
                        && colorOf(rightOf(sibling)) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = parentOf(node);
                } else {
                    if (colorOf(rightOf(sibling)) == BLACK) {
                        setColor(leftOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateRight(sibling);
                        sibling = rightOf(parent);
                    }

                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(rightOf(sibling), BLACK);
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node<T> sibling = leftOf(parent);

                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateRight(parent);
                    sibling = leftOf(parent);
                }

                if (colorOf(rightOf(sibling)) == BLACK
                        && colorOf(leftOf(sibling)) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = parentOf(node);
                } else {
                    if (colorOf(leftOf(sibling)) == BLACK) {
                        setColor(rightOf(sibling), BLACK);
                        setColor(sibling, RED);
                        rotateLeft(sibling);
                        sibling = leftOf(parent);
                    }

                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(leftOf(sibling), BLACK);
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }

        setColor(node, BLACK);
        setColor(root, BLACK);
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

    private static <E> Node<E> parentOf(Node<E> node) {
        return node == null ? null : node.parent;
    }

    private static <E> Node<E> leftOf(Node<E> node) {
        return node == null ? null : node.left;
    }

    private static <E> Node<E> rightOf(Node<E> node) {
        return node == null ? null : node.right;
    }

    private static <E> boolean colorOf(Node<E> node) {
        return node == null ? BLACK : node.red;
    }

    private static <E> void setColor(Node<E> node, boolean color) {
        if (node != null) {
            node.red = color;
        }
    }
}
