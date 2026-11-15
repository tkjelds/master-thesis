@RedBlackTree.ThreadSafe
public final class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = false;
    private static final boolean BLACK = true;

    private static final class Node<E> {
        E item;
        Node<E> left;
        Node<E> right;
        Node<E> parent;
        boolean color;

        Node(E item, boolean color, Node<E> parent) {
            this.item = item;
            this.color = color;
            this.parent = parent;
        }
    }

    private final Object monitor = new Object();
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

            Node<T> node = new Node<T>(item, RED, parent);
            if (parent == null) {
                root = node;
            } else if (comparison < 0) {
                parent.left = node;
            } else {
                parent.right = node;
            }

            count++;
            fixAfterInsertion(node);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Node<T> node = findNode(item);
            if (node == null) {
                return false;
            }
            deleteNode(node);
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
            copyInOrder(root, result, new int[] {0});
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

    private void copyInOrder(Node<T> node, Object[] result, int[] index) {
        if (node == null) {
            return;
        }
        copyInOrder(node.left, result, index);
        result[index[0]++] = node.item;
        copyInOrder(node.right, result, index);
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

    private boolean colorOf(Node<T> node) {
        return node == null ? BLACK : node.color;
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

    private void setColor(Node<T> node, boolean color) {
        if (node != null) {
            node.color = color;
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
        root.color = BLACK;
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

    private void deleteNode(Node<T> node) {
        Node<T> movedNode = node;
        boolean originalColor = movedNode.color;
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
            movedNode = minimum(node.right);
            originalColor = movedNode.color;
            replacement = movedNode.right;
            if (movedNode.parent == node) {
                replacementParent = movedNode;
                if (replacement != null) {
                    replacement.parent = movedNode;
                }
            } else {
                Node<T> oldParent = movedNode.parent;
                transplant(movedNode, movedNode.right);
                replacementParent = replacement != null ? replacement.parent : oldParent;
                movedNode.right = node.right;
                movedNode.right.parent = movedNode;
            }

            transplant(node, movedNode);
            movedNode.left = node.left;
            movedNode.left.parent = movedNode;
            movedNode.color = node.color;
        }

        count--;
        if (originalColor == BLACK) {
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
                    parent = node.parent;
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
                    parent = node.parent;
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
    }
}

interface Order<T> {
    int compare(T left, T right);
}
