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
        boolean color = RED;

        Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Object mutex = new Object();
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

        synchronized (mutex) {
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

            count++;
            fixAfterInsertion(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (mutex) {
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
            throw new NullPointerException();
        }

        synchronized (mutex) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (mutex) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (mutex) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return maximum(root).item;
        }
    }

    public int size() {
        synchronized (mutex) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (mutex) {
            Object[] result = new Object[count];
            writeInOrder(root, result, 0);
            return result;
        }
    }

    private int writeInOrder(Node<T> node, Object[] result, int index) {
        if (node == null) {
            return index;
        }
        index = writeInOrder(node.left, result, index);
        result[index++] = node.item;
        return writeInOrder(node.right, result, index);
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

    private void rotateLeft(Node<T> node) {
        Node<T> right = node.right;
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

    private void rotateRight(Node<T> node) {
        Node<T> left = node.left;
        node.left = left.right;
        if (left.right != null) {
            left.right.parent = node;
        }

        left.parent = node.parent;
        if (node.parent == null) {
            root = left;
        } else if (node == node.parent.right) {
            node.parent.right = left;
        } else {
            node.parent.left = left;
        }

        left.right = node;
        node.parent = left;
    }

    private void fixAfterInsertion(Node<T> node) {
        node.color = RED;

        while (node != root && colorOf(parentOf(node)) == RED) {
            if (parentOf(node) == leftOf(parentOf(parentOf(node)))) {
                Node<T> uncle = rightOf(parentOf(parentOf(node)));
                if (colorOf(uncle) == RED) {
                    setColor(parentOf(node), BLACK);
                    setColor(uncle, BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    node = parentOf(parentOf(node));
                } else {
                    if (node == rightOf(parentOf(node))) {
                        node = parentOf(node);
                        rotateLeft(node);
                    }
                    setColor(parentOf(node), BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    rotateRight(parentOf(parentOf(node)));
                }
            } else {
                Node<T> uncle = leftOf(parentOf(parentOf(node)));
                if (colorOf(uncle) == RED) {
                    setColor(parentOf(node), BLACK);
                    setColor(uncle, BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    node = parentOf(parentOf(node));
                } else {
                    if (node == leftOf(parentOf(node))) {
                        node = parentOf(node);
                        rotateRight(node);
                    }
                    setColor(parentOf(node), BLACK);
                    setColor(parentOf(parentOf(node)), RED);
                    rotateLeft(parentOf(parentOf(node)));
                }
            }
        }

        setColor(root, BLACK);
    }

    private void deleteNode(Node<T> target) {
        Node<T> moved = target;
        boolean originalColor = moved.color;
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
            originalColor = moved.color;
            replacement = moved.right;

            if (moved.parent == target) {
                replacementParent = moved;
                if (replacement != null) {
                    replacement.parent = moved;
                }
            } else {
                replacementParent = moved.parent;
                transplant(moved, moved.right);
                if (replacement != null) {
                    replacementParent = replacement.parent;
                }
                moved.right = target.right;
                moved.right.parent = moved;
            }

            transplant(target, moved);
            moved.left = target.left;
            moved.left.parent = moved;
            moved.color = target.color;
        }

        if (originalColor == BLACK) {
            fixAfterDeletion(replacement, replacementParent);
        }
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
        if (root != null) {
            root.color = BLACK;
        }
    }

    private boolean colorOf(Node<T> node) {
        return node == null ? BLACK : node.color;
    }

    private void setColor(Node<T> node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }

    private Node<T> parentOf(Node<T> node) {
        return node == null ? null : node.parent;
    }

    private Node<T> leftOf(Node<T> node) {
        return node == null ? null : node.left;
    }

    private Node<T> rightOf(Node<T> node) {
        return node == null ? null : node.right;
    }
}
