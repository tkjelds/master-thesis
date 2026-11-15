@RedBlackTree.ThreadSafe
public final class RedBlackTree<E extends Comparable<? super E>> {
    @interface ThreadSafe {}

    private static final class Node<T> {
        private T value;
        private Node<T> left;
        private Node<T> right;
        private Node<T> parent;
        private boolean red;

        private Node(T value, boolean red) {
            this.value = value;
            this.red = red;
        }
    }

    private final Object lock = new Object();
    private final Node<E> nil;
    private Node<E> root;
    private int size;

    public RedBlackTree() {
        nil = new Node<E>(null, false);
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
        root = nil;
    }

    public boolean add(E value) {
        requireValue(value);

        synchronized (lock) {
            Node<E> parent = nil;
            Node<E> current = root;
            int comparison = 0;

            while (current != nil) {
                parent = current;
                comparison = value.compareTo(current.value);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node<E> node = new Node<E>(value, true);
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

            insertFixup(node);
            size++;
            return true;
        }
    }

    public boolean insert(E value) {
        return add(value);
    }

    public boolean contains(E value) {
        requireValue(value);

        synchronized (lock) {
            return findNode(value) != nil;
        }
    }

    public boolean search(E value) {
        return contains(value);
    }

    public boolean remove(E value) {
        requireValue(value);

        synchronized (lock) {
            Node<E> node = findNode(value);
            if (node == nil) {
                return false;
            }

            Node<E> moved = node;
            boolean movedWasRed = moved.red;
            Node<E> fixupNode;

            if (node.left == nil) {
                fixupNode = node.right;
                transplant(node, node.right);
            } else if (node.right == nil) {
                fixupNode = node.left;
                transplant(node, node.left);
            } else {
                moved = minimumNode(node.right);
                movedWasRed = moved.red;
                fixupNode = moved.right;

                if (moved.parent == node) {
                    fixupNode.parent = moved;
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
                deleteFixup(fixupNode);
            }

            size--;
            root.parent = nil;
            nil.parent = nil;
            nil.red = false;
            return true;
        }
    }

    public boolean delete(E value) {
        return remove(value);
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public E minimum() {
        synchronized (lock) {
            return root == nil ? null : minimumNode(root).value;
        }
    }

    public E maximum() {
        synchronized (lock) {
            if (root == nil) {
                return null;
            }

            Node<E> node = root;
            while (node.right != nil) {
                node = node.right;
            }
            return node.value;
        }
    }

    public Object[] toArray() {
        synchronized (lock) {
            Object[] result = new Object[size];
            writeInOrder(root, result, 0);
            return result;
        }
    }

    public void clear() {
        synchronized (lock) {
            root = nil;
            size = 0;
            nil.parent = nil;
            nil.red = false;
        }
    }

    private void requireValue(E value) {
        if (value == null) {
            throw new IllegalArgumentException("Null values are not supported");
        }
    }

    private Node<E> findNode(E value) {
        Node<E> current = root;
        while (current != nil) {
            int comparison = value.compareTo(current.value);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return nil;
    }

    private Node<E> minimumNode(Node<E> node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private int writeInOrder(Node<E> node, Object[] result, int index) {
        if (node == nil) {
            return index;
        }
        index = writeInOrder(node.left, result, index);
        result[index++] = node.value;
        return writeInOrder(node.right, result, index);
    }

    private void insertFixup(Node<E> node) {
        while (node.parent.red) {
            if (node.parent == node.parent.parent.left) {
                Node<E> uncle = node.parent.parent.right;
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
                Node<E> uncle = node.parent.parent.left;
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
        root.parent = nil;
    }

    private void deleteFixup(Node<E> node) {
        while (node != root && !node.red) {
            if (node == node.parent.left) {
                Node<E> sibling = node.parent.right;

                if (sibling.red) {
                    sibling.red = false;
                    node.parent.red = true;
                    rotateLeft(node.parent);
                    sibling = node.parent.right;
                }

                if (!sibling.left.red && !sibling.right.red) {
                    if (sibling != nil) {
                        sibling.red = true;
                    }
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
                Node<E> sibling = node.parent.left;

                if (sibling.red) {
                    sibling.red = false;
                    node.parent.red = true;
                    rotateRight(node.parent);
                    sibling = node.parent.left;
                }

                if (!sibling.right.red && !sibling.left.red) {
                    if (sibling != nil) {
                        sibling.red = true;
                    }
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

    private void transplant(Node<E> oldNode, Node<E> newNode) {
        if (oldNode.parent == nil) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        newNode.parent = oldNode.parent;
    }

    private void rotateLeft(Node<E> node) {
        Node<E> pivot = node.right;
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

    private void rotateRight(Node<E> node) {
        Node<E> pivot = node.left;
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
