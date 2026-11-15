interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private final Object lock = new Object();
    private final Order<T> order;
    private final Node nil;
    private Node root;
    private int size;

    private final class Node {
        private T item;
        private Node left;
        private Node right;
        private Node parent;
        private boolean red;

        private Node(T item, boolean red) {
            this.item = item;
            this.red = red;
        }
    }

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }

        this.order = order;
        this.nil = new Node(null, false);
        this.nil.left = this.nil;
        this.nil.right = this.nil;
        this.nil.parent = this.nil;
        this.root = this.nil;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node parent = nil;
            Node current = root;
            int comparison = 0;

            while (current != nil) {
                parent = current;
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node inserted = new Node(item, true);
            inserted.left = nil;
            inserted.right = nil;
            inserted.parent = parent;

            if (parent == nil) {
                root = inserted;
            } else if (order.compare(item, parent.item) < 0) {
                parent.left = inserted;
            } else {
                parent.right = inserted;
            }

            size++;
            insertFix(inserted);
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node target = root;
            while (target != nil) {
                int comparison = order.compare(item, target.item);
                if (comparison == 0) {
                    break;
                }
                target = comparison < 0 ? target.left : target.right;
            }

            if (target == nil) {
                return false;
            }

            Node moved = target;
            boolean movedWasRed = moved.red;
            Node replacement;

            if (target.left == nil) {
                replacement = target.right;
                transplant(target, target.right);
            } else if (target.right == nil) {
                replacement = target.left;
                transplant(target, target.left);
            } else {
                moved = minimum(target.right);
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

            size--;
            if (!movedWasRed) {
                deleteFix(replacement);
            }
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node current = root;
            while (current != nil) {
                int comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return true;
                }
                current = comparison < 0 ? current.left : current.right;
            }
            return false;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException();
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == nil) {
                throw new IllegalStateException();
            }

            Node current = root;
            while (current.right != nil) {
                current = current.right;
            }
            return current.item;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            snapshotInto(root, result, 0);
            return result;
        }
    }

    private int snapshotInto(Node node, Object[] result, int index) {
        if (node == nil) {
            return index;
        }

        index = snapshotInto(node.left, result, index);
        result[index++] = node.item;
        return snapshotInto(node.right, result, index);
    }

    private Node minimum(Node node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private void transplant(Node oldNode, Node newNode) {
        if (oldNode.parent == nil) {
            root = newNode;
        } else if (oldNode == oldNode.parent.left) {
            oldNode.parent.left = newNode;
        } else {
            oldNode.parent.right = newNode;
        }
        newNode.parent = oldNode.parent;
    }

    private void rotateLeft(Node node) {
        Node pivot = node.right;
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

    private void rotateRight(Node node) {
        Node pivot = node.left;
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

    private void insertFix(Node node) {
        while (node.parent.red) {
            if (node.parent == node.parent.parent.left) {
                Node uncle = node.parent.parent.right;
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
                Node uncle = node.parent.parent.left;
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

    private void deleteFix(Node node) {
        while (node != root && !node.red) {
            if (node == node.parent.left) {
                Node sibling = node.parent.right;
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
                Node sibling = node.parent.left;
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
        nil.red = false;
    }
}
