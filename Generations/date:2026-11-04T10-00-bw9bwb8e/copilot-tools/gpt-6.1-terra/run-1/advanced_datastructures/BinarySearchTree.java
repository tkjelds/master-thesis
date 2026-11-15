interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        final E item;
        Node<E> left;
        Node<E> right;

        Node(E item) {
            this.item = item;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
    private Node<T> root;
    private int count;

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
            Node<T> parent = null;
            Node<T> current = root;
            int comparison = 0;

            while (current != null) {
                parent = current;
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node<T> node = new Node<T>(item);
            if (parent == null) {
                root = node;
            } else if (comparison < 0) {
                parent.left = node;
            } else {
                parent.right = node;
            }
            count++;
            return true;
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

            Node<T> replacement;
            if (current.left == null) {
                replacement = current.right;
            } else if (current.right == null) {
                replacement = current.left;
            } else {
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
                replacement = successor;
            }

            if (parent == null) {
                root = replacement;
            } else if (parent.left == current) {
                parent.left = replacement;
            } else {
                parent.right = replacement;
            }
            count--;
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
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[count];
            Node<?>[] stack = new Node<?>[count];
            Node<?> current = root;
            int depth = 0;
            int index = 0;

            while (current != null || depth != 0) {
                while (current != null) {
                    stack[depth++] = current;
                    current = current.left;
                }
                current = stack[--depth];
                result[index++] = current.item;
                current = current.right;
            }
            return result;
        }
    }
}
