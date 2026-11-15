interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E item;
        private Node<E> left;
        private Node<E> right;
        private Node<E> parent;
        private boolean red;

        private Node(E item) {
            this.item = item;
        }
    }

    private final Order<T> order;
    private final Object monitor = new Object();
    private final Node<T> nil;
    private Node<T> root;
    private int count;

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        nil = new Node<T>(null);
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
        nil.red = false;
        root = nil;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
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

            Node<T> inserted = new Node<T>(item);
            inserted.left = nil;
            inserted.right = nil;
            inserted.parent = parent;
            inserted.red = true;
            if (parent == nil) {
                root = inserted;
            } else if (comparison < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }
            insertFixup(inserted);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Node<T> removed = find(item);
            if (removed == nil) {
                return false;
            }

            Node<T> moved = removed;
            boolean movedWasRed = moved.red;
            Node<T> replacement;
            if (removed.left == nil) {
                replacement = removed.right;
                transplant(removed, removed.right);
            } else if (removed.right == nil) {
                replacement = removed.left;
                transplant(removed, removed.left);
            } else {
                moved = minimum(removed.right);
                movedWasRed = moved.red;
                replacement = moved.right;
                if (moved.parent == removed) {
                    replacement.parent = moved;
                } else {
                    transplant(moved, moved.right);
                    moved.right = removed.right;
                    moved.right.parent = moved;
                }
                transplant(removed, moved);
                moved.left = removed.left;
                moved.left.parent = moved;
                moved.red = removed.red;
            }

            if (!movedWasRed) {
                deleteFixup(replacement);
            }
            count--;
            if (root != nil) {
                root.parent = nil;
            }
            nil.parent = nil;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            return find(item) != nil;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == nil) {
                throw new IllegalStateException("tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == nil) {
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
            int[] index = new int[] {0};
            copyInOrder(root, result, index);
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

    private Node<T> maximum(Node<T> node) {
        while (node.right != nil) {
            node = node.right;
        }
        return node;
    }

    private void copyInOrder(Node<T> node, Object[] result, int[] index) {
        if (node == nil) {
            return;
        }
        copyInOrder(node.left, result, index);
        result[index[0]++] = node.item;
        copyInOrder(node.right, result, index);
    }

    private void insertFixup(Node<T> node) {
        while (node.parent.red) {
            if (node.parent == node.parent.parent.left) {
                Node<T> uncle = node.parent.parent.right;
                if (uncle.red) {
                    setRed(node.parent, false);
                    setRed(uncle, false);
                    setRed(node.parent.parent, true);
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.right) {
                        node = node.parent;
                        rotateLeft(node);
                    }
                    setRed(node.parent, false);
                    setRed(node.parent.parent, true);
                    rotateRight(node.parent.parent);
                }
            } else {
                Node<T> uncle = node.parent.parent.left;
                if (uncle.red) {
                    setRed(node.parent, false);
                    setRed(uncle, false);
                    setRed(node.parent.parent, true);
                    node = node.parent.parent;
                } else {
                    if (node == node.parent.left) {
                        node = node.parent;
                        rotateRight(node);
                    }
                    setRed(node.parent, false);
                    setRed(node.parent.parent, true);
                    rotateLeft(node.parent.parent);
                }
            }
        }
        setRed(root, false);
    }

    private void deleteFixup(Node<T> node) {
        while (node != root && !node.red) {
            if (node == node.parent.left) {
                Node<T> sibling = node.parent.right;
                if (sibling.red) {
                    setRed(sibling, false);
                    setRed(node.parent, true);
                    rotateLeft(node.parent);
                    sibling = node.parent.right;
                }
                if (!sibling.left.red && !sibling.right.red) {
                    setRed(sibling, true);
                    node = node.parent;
                } else {
                    if (!sibling.right.red) {
                        setRed(sibling.left, false);
                        setRed(sibling, true);
                        rotateRight(sibling);
                        sibling = node.parent.right;
                    }
                    setRed(sibling, node.parent.red);
                    setRed(node.parent, false);
                    setRed(sibling.right, false);
                    rotateLeft(node.parent);
                    node = root;
                }
            } else {
                Node<T> sibling = node.parent.left;
                if (sibling.red) {
                    setRed(sibling, false);
                    setRed(node.parent, true);
                    rotateRight(node.parent);
                    sibling = node.parent.left;
                }
                if (!sibling.right.red && !sibling.left.red) {
                    setRed(sibling, true);
                    node = node.parent;
                } else {
                    if (!sibling.left.red) {
                        setRed(sibling.right, false);
                        setRed(sibling, true);
                        rotateLeft(sibling);
                        sibling = node.parent.left;
                    }
                    setRed(sibling, node.parent.red);
                    setRed(node.parent, false);
                    setRed(sibling.left, false);
                    rotateRight(node.parent);
                    node = root;
                }
            }
        }
        setRed(node, false);
    }

    private void setRed(Node<T> node, boolean red) {
        if (node != nil) {
            node.red = red;
        }
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

    private void rotateLeft(Node<T> node) {
        Node<T> pivot = node.right;
        node.right = pivot.left;
        pivot.left.parent = node;
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
        pivot.right.parent = node;
        pivot.parent = node.parent;
        if (node.parent == nil) {
            root = pivot;
        } else if (node == node.parent.left) {
            node.parent.left = pivot;
        } else {
            node.parent.right = pivot;
        }
        pivot.right = node;
        node.parent = pivot;
    }
}
