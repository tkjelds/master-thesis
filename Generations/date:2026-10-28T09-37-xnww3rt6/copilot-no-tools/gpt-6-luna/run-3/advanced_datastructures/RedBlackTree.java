interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final Order<T> order;
    private final Object lock = new Object();
    private Node root;
    private int elementCount;

    private final class Node {
        T item;
        Node left;
        Node right;
        Node parent;
        boolean color = RED;

        Node(T item, Node parent) {
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
            } else if (order.compare(item, parent.item) < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }

            elementCount++;
            fixAfterInsertion(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            Node node = findNode(item);
            if (node == null) {
                return false;
            }

            deleteNode(node);
            elementCount--;
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
            return maximum(root).item;
        }
    }

    public int size() {
        synchronized (lock) {
            return elementCount;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[elementCount];
            copyInOrder(root, result, new int[] {0});
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

    private void copyInOrder(Node node, Object[] result, int[] index) {
        if (node == null) {
            return;
        }
        copyInOrder(node.left, result, index);
        result[index[0]++] = node.item;
        copyInOrder(node.right, result, index);
    }

    private void fixAfterInsertion(Node node) {
        node.color = RED;

        while (node != root && colorOf(parentOf(node)) == RED) {
            if (parentOf(node) == leftOf(parentOf(parentOf(node)))) {
                Node uncle = rightOf(parentOf(parentOf(node)));
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
                Node uncle = leftOf(parentOf(parentOf(node)));
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

    private void deleteNode(Node node) {
        Node moved = node;
        boolean originalColor = moved.color;
        Node replacement;
        Node replacementParent;

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
            originalColor = moved.color;
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
            moved.color = node.color;
        }

        if (originalColor == BLACK) {
            fixAfterDeletion(replacement, replacementParent);
        }
    }

    private void fixAfterDeletion(Node node, Node parent) {
        while (node != root && colorOf(node) == BLACK) {
            if (parent == null) {
                break;
            }

            if (node == leftOf(parent)) {
                Node sibling = rightOf(parent);

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
                Node sibling = leftOf(parent);

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

    private static boolean colorOf(RedBlackTree<?>.Node node) {
        return node == null ? BLACK : node.color;
    }

    private static void setColor(RedBlackTree<?>.Node node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }

    private static RedBlackTree<?>.Node parentOf(RedBlackTree<?>.Node node) {
        return node == null ? null : node.parent;
    }

    private static RedBlackTree<?>.Node leftOf(RedBlackTree<?>.Node node) {
        return node == null ? null : node.left;
    }

    private static RedBlackTree<?>.Node rightOf(RedBlackTree<?>.Node node) {
        return node == null ? null : node.right;
    }
}
