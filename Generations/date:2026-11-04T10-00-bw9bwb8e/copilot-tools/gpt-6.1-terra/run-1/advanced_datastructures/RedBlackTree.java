interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        boolean red;
        Node<E> parent;
        Node<E> left;
        Node<E> right;

        Node(E item, boolean red) {
            this.item = item;
            this.red = red;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private final Node<T> nil;
    private Node<T> root;
    private int count;

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        nil = new Node<T>(null, false);
        nil.parent = nil;
        nil.left = nil;
        nil.right = nil;
        root = nil;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> parent = nil;
            Node<T> current = root;
            int comparison = 0;

            while (current != nil) {
                parent = current;
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node<T> node = new Node<T>(item, true);
            node.parent = parent;
            node.left = nil;
            node.right = nil;

            if (parent == nil) {
                root = node;
            } else if (comparison < 0) {
                parent.left = node;
            } else {
                parent.right = node;
            }

            fixAfterInsert(node);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> node = find(item);
            if (node == nil) {
                return false;
            }

            Node<T> moved = node;
            boolean movedWasRed = moved.red;
            Node<T> replacement;

            if (node.left == nil) {
                replacement = node.right;
                transplant(node, node.right);
            } else if (node.right == nil) {
                replacement = node.left;
                transplant(node, node.left);
            } else {
                moved = minimum(node.right);
                movedWasRed = moved.red;
                replacement = moved.right;

                if (moved.parent == node) {
                    replacement.parent = moved;
                } else {
                    transplant(moved, moved.right);
                    moved.right = node.right;
                    moved.right.parent = moved;
                }

                transplant(node, moved);
                moved.left = node.left;
                moved.left.parent = moved;
                moved.red = node.red;
            }

            if (!movedWasRed) {
                fixAfterDelete(replacement);
            }
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return find(item) != nil;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException("Tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException("Tree is empty");
            }
            Node<T> node = root;
            while (node.right != nil) {
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

    private Node<T> find(T item) {
        Node<T> current = root;
        while (current != nil) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return nil;
    }

    private Node<T> minimum(Node<T> node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == nil) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
    }

    private void rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
        node.right = pivot.left;
        if (pivot.left != nil) {
            pivot.left.parent = node;
        }
        pivot.parent = node.parent;
        if (node.parent == nil) {
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
        if (pivot.right != nil) {
            pivot.right.parent = node;
        }
        pivot.parent = node.parent;
        if (node.parent == nil) {
            root = pivot;
        } else if (node == node.parent.right) {
            node.parent.right = pivot;
        } else {
            node.parent.left = pivot;
        }
        pivot.right = node;
        node.parent = pivot;
    }

    private void fixAfterInsert(Node<T> node) {
        while (node.parent.red) {
            if (node.parent == node.parent.parent.left) {
                Node<T> uncle = node.parent.parent.right;
                if (uncle.red) {
                    node.parent.red = false;
                    uncle.red = false;
                    node.parent.parent.red = true;
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.right) {
                        node = node.parent;
                        rotateLeft(node);
                    }
                    node.parent.red = false;
                    node.parent.parent.red = true;
                    rotateRight(node.parent.parent);
                }
            } else {
                Node<T> uncle = node.parent.parent.left;
                if (uncle.red) {
                    node.parent.red = false;
                    uncle.red = false;
                    node.parent.parent.red = true;
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.left) {
                        node = node.parent;
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

    private void transplant(Node<T> oldNode, Node<T> newNode) {
        if (oldNode.parent == nil) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        newNode.parent = oldNode.parent;
    }

    private void fixAfterDelete(Node<T> node) {
        while (node != root && !node.red) {
            if (node == node.parent.left) {
                Node<T> sibling = node.parent.right;
                if (sibling.red) {
                    sibling.red = false;
                    node.parent.red = true;
                    rotateLeft(node.parent);
                    sibling = node.parent.right;
                }
                if (!sibling.left.red && !sibling.right.red) {
                    sibling.red = true;
                    node = node.parent;
                } else {
                    if (!sibling.right.red) {
                        sibling.left.red = false;
                        sibling.red = true;
                        rotateRight(sibling);
                        sibling = node.parent.right;
                    }
                    sibling.red = node.parent.red;
                    node.parent.red = false;
                    sibling.right.red = false;
                    rotateLeft(node.parent);
                    node = root;
                }
            } else {
                Node<T> sibling = node.parent.left;
                if (sibling.red) {
                    sibling.red = false;
                    node.parent.red = true;
                    rotateRight(node.parent);
                    sibling = node.parent.left;
                }
                if (!sibling.right.red && !sibling.left.red) {
                    sibling.red = true;
                    node = node.parent;
                } else {
                    if (!sibling.left.red) {
                        sibling.right.red = false;
                        sibling.red = true;
                        rotateLeft(sibling);
                        sibling = node.parent.left;
                    }
                    sibling.red = node.parent.red;
                    node.parent.red = false;
                    sibling.left.red = false;
                    rotateRight(node.parent);
                    node = root;
                }
            }
        }
        node.red = false;
    }
}
