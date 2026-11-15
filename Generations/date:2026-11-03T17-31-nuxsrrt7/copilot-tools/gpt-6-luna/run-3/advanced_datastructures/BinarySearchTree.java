interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        E item;
        Node<E> left;
        Node<E> right;

        Node(E item) {
            this.item = item;
        }
    }

    private final Object lock = new Object();
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

        synchronized (lock) {
            if (root == null) {
                root = new Node<T>(item);
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

        synchronized (lock) {
            Node<T> parent = null;
            Node<T> current = root;

            while (current != null) {
                int comparison = order.compare(item, current.item);
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

                if (successorParent != current) {
                    successorParent.left = successor.right;
                    successor.right = current.right;
                }
                successor.left = current.left;

                if (parent == null) {
                    root = successor;
                } else if (parent.left == current) {
                    parent.left = successor;
                } else {
                    parent.right = successor;
                }
            } else {
                Node<T> replacement =
                        current.left != null ? current.left : current.right;

                if (parent == null) {
                    root = replacement;
                } else if (parent.left == current) {
                    parent.left = replacement;
                } else {
                    parent.right = replacement;
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

        synchronized (lock) {
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
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node<T> current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node<T> current = root;
            while (current.right != null) {
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
            copyInOrder(root, result, 0);
            return result;
        }
    }

    private int copyInOrder(Node<T> node, Object[] result, int index) {
        if (node == null) {
            return index;
        }

        index = copyInOrder(node.left, result, index);
        result[index++] = node.item;
        return copyInOrder(node.right, result, index);
    }
}
