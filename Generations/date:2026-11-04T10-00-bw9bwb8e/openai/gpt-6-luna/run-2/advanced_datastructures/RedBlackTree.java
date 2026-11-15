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

        synchronized (this) {
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

        synchronized (this) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            return maximum(root).item;
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
            fillSnapshot(root, result, new int[] { 0 });
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

    private void fillSnapshot(Node<T> node, Object[] result, int[] index) {
        if (node == null) {
            return;
        }
        fillSnapshot(node.left, result, index);
        result[index[0]++] = node.item;
        fillSnapshot(node.right, result, index);
    }

    private void fixAfterInsertion(Node<T> node) {
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

        target.left = null;
        target.right = null;
        target.parent = null;

        if (originalColor == BLACK) {
            fixAfterDeletion(replacement, replacementParent);
        }
    }

    private void fixAfterDeletion(Node<T> node, Node<T> parent) {
        while (node != root && colorOf(node) == BLACK) {
            if (parent == null) {
                break;
            }

            if (node == parent.left) {
                Node<T> sibling = parent.right;
                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateLeft(parent);
                    sibling = parent.right;
                }

                if (sibling == null) {
                    node = parent;
                    parent = node.parent;
                    continue;
                }

                if (colorOf(sibling.left) == BLACK
                        && colorOf(sibling.right) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (colorOf(sibling.right) == BLACK) {
                        setColor(sibling.left, BLACK);
                        setColor(sibling, RED);
                        rotateRight(sibling);
                        sibling = parent.right;
                    }
                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(sibling.right, BLACK);
                    rotateLeft(parent);
                    node = root;
                    parent = null;
                }
            } else {
                Node<T> sibling = parent.left;
                if (colorOf(sibling) == RED) {
                    setColor(sibling, BLACK);
                    setColor(parent, RED);
                    rotateRight(parent);
                    sibling = parent.left;
                }

                if (sibling == null) {
                    node = parent;
                    parent = node.parent;
                    continue;
                }

                if (colorOf(sibling.right) == BLACK
                        && colorOf(sibling.left) == BLACK) {
                    setColor(sibling, RED);
                    node = parent;
                    parent = node.parent;
                } else {
                    if (colorOf(sibling.left) == BLACK) {
                        setColor(sibling.right, BLACK);
                        setColor(sibling, RED);
                        rotateLeft(sibling);
                        sibling = parent.left;
                    }
                    setColor(sibling, colorOf(parent));
                    setColor(parent, BLACK);
                    setColor(sibling.left, BLACK);
                    rotateRight(parent);
                    node = root;
                    parent = null;
                }
            }
        }
        setColor(node, BLACK);
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
        return node == null ? BLACK : node.color;
    }

    private static <E> void setColor(Node<E> node, boolean color) {
        if (node != null) {
            node.color = color;
        }
    }
}
