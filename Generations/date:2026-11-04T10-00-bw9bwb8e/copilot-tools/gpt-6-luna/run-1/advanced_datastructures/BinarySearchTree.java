interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private final E item;
        private Node<E> left;
        private Node<E> right;
        private Node<E> parent;

        private Node(E item, Node<E> parent) {
            this.item = item;
            this.parent = parent;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException();
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (monitor) {
            if (root == null) {
                root = new Node<T>(item, null);
                size++;
                return true;
            }

            Node<T> current = root;
            while (true) {
                int comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                } else if (comparison < 0) {
                    if (current.left == null) {
                        current.left = new Node<T>(item, current);
                        size++;
                        return true;
                    }
                    current = current.left;
                } else {
                    if (current.right == null) {
                        current.right = new Node<T>(item, current);
                        size++;
                        return true;
                    }
                    current = current.right;
                }
            }
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (monitor) {
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    removeNode(current);
                    size--;
                    return true;
                }
                current = comparison < 0 ? current.left : current.right;
            }
            return false;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (monitor) {
            Node<T> current = root;
            while (current != null) {
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
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return minimum(root).item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException();
            }
            return maximum(root).item;
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            Node<T> current = root == null ? null : minimum(root);
            int index = 0;
            while (current != null) {
                result[index++] = current.item;
                current = successor(current);
            }
            return result;
        }
    }

    private void removeNode(Node<T> node) {
        if (node.left == null) {
            transplant(node, node.right);
        } else if (node.right == null) {
            transplant(node, node.left);
        } else {
            Node<T> replacement = minimum(node.right);
            if (replacement.parent != node) {
                transplant(replacement, replacement.right);
                replacement.right = node.right;
                replacement.right.parent = replacement;
            }
            transplant(node, replacement);
            replacement.left = node.left;
            replacement.left.parent = replacement;
        }
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

    private Node<T> successor(Node<T> node) {
        if (node.right != null) {
            return minimum(node.right);
        }

        Node<T> parent = node.parent;
        while (parent != null && node == parent.right) {
            node = parent;
            parent = parent.parent;
        }
        return parent;
    }
}
