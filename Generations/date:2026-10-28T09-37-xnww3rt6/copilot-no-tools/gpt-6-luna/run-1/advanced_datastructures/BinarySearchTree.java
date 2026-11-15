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
            throw new NullPointerException();
        }

        synchronized (monitor) {
            Node<T> parent = null;
            Node<T> current = root;
            boolean isLeftChild = false;

            while (current != null) {
                int comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    break;
                }

                parent = current;
                isLeftChild = comparison < 0;
                current = isLeftChild ? current.left : current.right;
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

                current.item = successor.item;
                if (successorParent == current) {
                    successorParent.right = successor.right;
                } else {
                    successorParent.left = successor.right;
                }
            } else {
                Node<T> replacement =
                    current.left != null ? current.left : current.right;

                if (parent == null) {
                    root = replacement;
                } else if (isLeftChild) {
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

            Node<T> current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.item;
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException();
            }

            Node<T> current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.item;
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
            Object[] stack = new Object[size];
            int stackSize = 0;
            int resultIndex = 0;
            Node<T> current = root;

            while (current != null || stackSize > 0) {
                while (current != null) {
                    stack[stackSize++] = current;
                    current = current.left;
                }

                current = (Node<T>) stack[--stackSize];
                result[resultIndex++] = current.item;
                current = current.right;
            }

            return result;
        }
    }
}

interface Order<T> {
    int compare(T left, T right);
}
