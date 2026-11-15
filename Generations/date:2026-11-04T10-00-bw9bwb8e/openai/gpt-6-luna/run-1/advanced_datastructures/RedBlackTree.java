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
        boolean red;

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
            throw new NullPointerException();
        }

        this.order = order;
        this.nil = new Node<T>(null, false);
        this.nil.left = this.nil;
        this.nil.right = this.nil;
        this.nil.parent = this.nil;
        this.root = this.nil;
        this.count = 0;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
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

            Node<T> added = new Node<T>(item, true);
            added.left = nil;
            added.right = nil;
            added.parent = parent;

            if (parent == nil) {
                root = added;
            } else if (order.compare(item, parent.item) < 0) {
                parent.left = added;
            } else {
                parent.right = added;
            }

            count++;
            insertFixup(added);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node<T> target = findNode(item);
            if (target == nil) {
                return false;
            }

            Node<T> moved = target;
            boolean movedWasRed = moved.red;
            Node<T> replacement;

            if (target.left == nil) {
                replacement = target.right;
                transplant(target, target.right);
            } else if (target.right == nil) {
                replacement = target.left;
                transplant(target, target.left);
            } else {
                moved = minimumNode(target.right);
                movedWasRed = moved.red;
                replacement = moved.right;

                if (moved.parent == target) {
                    replacement.parent = moved;
                } else {
                    transplant(moved, moved.right);
                    moved.right = target.right;
                    moved.right.parent = moved;
                }

                transplant(target, moved);
                moved.left = target.left;
                moved.left.parent = moved;
                moved.red = target.red;
            }

            count--;
            if (!movedWasRed) {
                deleteFixup(replacement);
            }

            root.parent = nil;
            nil.parent = nil;
            nil.red = false;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            return findNode(item) != nil;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException();
            }
            return minimumNode(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException();
            }

            Node<T> current = root;
            while (current.right != nil) {
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

    private Node<T> findNode(T item) {
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

    private Node<T> minimumNode(Node<T> node) {
        Node<T> current = node;
        while (current.left != nil) {
            current = current.left;
        }
        return current;
    }

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == nil) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
    }

    private void insertFixup(Node<T> node) {
        Node<T> current = node;

        while (current.parent.red) {
            if (current.parent == current.parent.parent.left) {
                Node<T> uncle = current.parent.parent.right;
                if (uncle.red) {
                    current.parent.red = false;
                    uncle.red = false;
                    current.parent.parent.red = true;
                    current = current.parent.parent;
                } else {
                    if (current == current.parent.right) {
                        current = current.parent;
                        rotateLeft(current);
                    }
                    current.parent.red = false;
                    current.parent.parent.red = true;
                    rotateRight(current.parent.parent);
                }
            } else {
                Node<T> uncle = current.parent.parent.left;
                if (uncle.red) {
                    current.parent.red = false;
                    uncle.red = false;
                    current.parent.parent.red = true;
                    current = current.parent.parent;
                } else {
                    if (current == current.parent.left) {
                        current = current.parent;
                        rotateRight(current);
                    }
                    current.parent.red = false;
                    current.parent.parent.red = true;
                    rotateLeft(current.parent.parent);
                }
            }
        }

        root.red = false;
        root.parent = nil;
    }

    private void deleteFixup(Node<T> node) {
        Node<T> current = node;

        while (current != root && !current.red) {
            if (current == current.parent.left) {
                Node<T> sibling = current.parent.right;

                if (sibling.red) {
                    sibling.red = false;
                    current.parent.red = true;
                    rotateLeft(current.parent);
                    sibling = current.parent.right;
                }

                if (!sibling.left.red && !sibling.right.red) {
                    if (sibling != nil) {
                        sibling.red = true;
                    }
                    current = current.parent;
                } else {
                    if (!sibling.right.red) {
                        sibling.left.red = false;
                        if (sibling != nil) {
                            sibling.red = true;
                        }
                        rotateRight(sibling);
                        sibling = current.parent.right;
                    }

                    sibling.red = current.parent.red;
                    current.parent.red = false;
                    sibling.right.red = false;
                    rotateLeft(current.parent);
                    current = root;
                }
            } else {
                Node<T> sibling = current.parent.left;

                if (sibling.red) {
                    sibling.red = false;
                    current.parent.red = true;
                    rotateRight(current.parent);
                    sibling = current.parent.left;
                }

                if (!sibling.right.red && !sibling.left.red) {
                    if (sibling != nil) {
                        sibling.red = true;
                    }
                    current = current.parent;
                } else {
                    if (!sibling.left.red) {
                        sibling.right.red = false;
                        if (sibling != nil) {
                            sibling.red = true;
                        }
                        rotateLeft(sibling);
                        sibling = current.parent.left;
                    }

                    sibling.red = current.parent.red;
                    current.parent.red = false;
                    sibling.left.red = false;
                    rotateRight(current.parent);
                    current = root;
                }
            }
        }

        current.red = false;
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
}
