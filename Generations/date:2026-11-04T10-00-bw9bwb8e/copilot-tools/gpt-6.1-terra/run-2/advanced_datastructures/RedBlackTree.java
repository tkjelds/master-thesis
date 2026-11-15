interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
public class RedBlackTree<T> {
    @interface ThreadSafe {}

    private final class Node {
        T item;
        boolean red;
        Node left;
        Node right;
        Node parent;

        Node(T item, boolean red) {
            this.item = item;
            this.red = red;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private final Node nil;
    private Node root;
    private int count;

    public RedBlackTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
        nil = new Node(null, false);
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
        root = nil;
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

            Node node = new Node(item, true);
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
            throw new NullPointerException();
        }
        synchronized (lock) {
            Node node = find(item);
            if (node == nil) {
                return false;
            }

            Node removed = node;
            boolean removedWasRed = removed.red;
            Node replacement;

            if (node.left == nil) {
                replacement = node.right;
                transplant(node, node.right);
            } else if (node.right == nil) {
                replacement = node.left;
                transplant(node, node.left);
            } else {
                removed = minimum(node.right);
                removedWasRed = removed.red;
                replacement = removed.right;

                if (removed.parent == node) {
                    replacement.parent = removed;
                } else {
                    transplant(removed, removed.right);
                    removed.right = node.right;
                    removed.right.parent = removed;
                }

                transplant(node, removed);
                removed.left = node.left;
                removed.left.parent = removed;
                removed.red = node.red;
            }

            if (!removedWasRed) {
                fixDeletion(replacement);
            }
            count--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
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
            Node current = root;
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

    private Node find(T item) {
        Node current = root;
        while (current != nil) {
            int comparison = order.compare(item, current.item);
            if (comparison == 0) {
                return current;
            }
            current = comparison < 0 ? current.left : current.right;
        }
        return nil;
    }

    private Node minimum(Node node) {
        while (node.left != nil) {
            node = node.left;
        }
        return node;
    }

    private int copyInOrder(Node node, Object[] result, int index) {
        if (node == nil) {
            return index;
        }
        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
    }

    private void rotateLeft(Node node) {
        Node child = node.right;
        node.right = child.left;
        if (child.left != nil) {
            child.left.parent = node;
        }
        child.parent = node.parent;
        if (node.parent == nil) {
            root = child;
        } else if (node == node.parent.left) {
            node.parent.left = child;
        } else {
            node.parent.right = child;
        }
        child.left = node;
        node.parent = child;
    }

    private void rotateRight(Node node) {
        Node child = node.left;
        node.left = child.right;
        if (child.right != nil) {
            child.right.parent = node;
        }
        child.parent = node.parent;
        if (node.parent == nil) {
            root = child;
        } else if (node == node.parent.right) {
            node.parent.right = child;
        } else {
            node.parent.left = child;
        }
        child.right = node;
        node.parent = child;
    }

    private void fixInsertion(Node node) {
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

    private void fixDeletion(Node node) {
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
                Node sibling = node.parent.left;
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
