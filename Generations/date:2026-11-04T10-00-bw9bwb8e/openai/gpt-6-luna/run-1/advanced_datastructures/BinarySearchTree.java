interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E element;
        Node<E> left;
        Node<E> right;

        Node(E element) {
            this.element = element;
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private Node<T> root;
    private int size;

    public BinarySearchTree(Order<T> order) {
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (root == null) {
                root = new Node<T>(item);
                size++;
                return true;
            }

            Node<T> current = root;
            while (true) {
                int comparison = order.compare(item, current.element);
                if (comparison == 0) {
                    return false;
                } else if (comparison < 0) {
                    if (current.left == null) {
                        current.left = new Node<T>(item);
                        size++;
                        return true;
                    }
                    current = current.left;
                } else {
                    if (current.right == null) {
                        current.right = new Node<T>(item);
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
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            Node<T> parent = null;
            Node<T> current = root;

            while (current != null) {
                int comparison = order.compare(item, current.element);
                if (comparison == 0) {
                    break;
                }

                parent = current;
                current = comparison < 0 ? current.left : current.right;
            }

            if (current == null) {
                return false;
            }

            if (current.left != null && current.right != null) {
                Node<T> successorParent = current;
                Node<T> successor = current.right;
                while (successor.left != null) {
                    successorParent = successor;
                    successor = successor.left;
                }

                current.element = successor.element;
                if (successorParent == current) {
                    successorParent.right = successor.right;
                } else {
                    successorParent.left = successor.right;
                }
            } else {
                Node<T> child = current.left != null ? current.left : current.right;
                if (parent == null) {
                    root = child;
                } else if (parent.left == current) {
                    parent.left = child;
                } else {
                    parent.right = child;
                }
            }

            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            Node<T> current = root;
            while (current != null) {
                int comparison = order.compare(item, current.element);
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
                throw new IllegalStateException("Tree is empty");
            }

            Node<T> current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.element;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node<T> current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.element;
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
            copyInOrder(root, result, 0);
            return result;
        }
    }

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == null) {
            return index;
        }

        index = copyInOrder(node.left, result, index);
        result[index++] = node.element;
        return copyInOrder(node.right, result, index);
    }
}
