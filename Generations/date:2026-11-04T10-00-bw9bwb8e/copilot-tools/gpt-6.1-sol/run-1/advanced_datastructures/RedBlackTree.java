interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public final class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        boolean red;
        Node<E> left;
        Node<E> right;
        Node<E> parent;

        Node(E item, boolean red) {
            this.item = item;
            this.red = red;
        }
    }

    private final Object monitor = new Object();
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
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
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

            Node<T> node = new Node<T>(item, true);
            node.left = nil;
            node.right = nil;
            node.parent = parent;

            if (parent == nil) {
                root = node;
            } else if (comparison < 0) {
                parent.left = node;
            } else {
                parent.right = node;
            }

            fixInsertion(node);
            count++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            Node<T> target = find(item);
            if (target == nil) {
                return false;
            }

            Node<T> moved = target;
            boolean removedWasRed = moved.red;
            Node<T> replacement;

            if (target.left == nil) {
                replacement = target.right;
                transplant(target, target.right);
            } else if (target.right == nil) {
                replacement = target.left;
                transplant(target, target.left);
            } else {
                moved = minimum(target.right);
                removedWasRed = moved.red;
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

            if (!removedWasRed) {
                fixDeletion(replacement);
            }

            count--;
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
                throw new IllegalStateException("Tree is empty");
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == nil) {
                throw new IllegalStateException("Tree is empty");
            }
            Node<T> current = root;
            while (current.right != nil) {
                current = current.right;
            }
            return current.item;
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

    private void transplant(Node<T> oldNode, Node<T> newNode) {
        if (oldNode.parent == nil) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        // The sentinel temporarily records the deletion repair position.
        newNode.parent = oldNode.parent;
    }

    private void fixInsertion(Node<T> node) {
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

    private void fixDeletion(Node<T> node) {
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
