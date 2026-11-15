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
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                parent = current;
                current = comparison < 0 ? current.left : current.right;
            }

            // The public size type cannot represent a larger tree.
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree size exceeds int range");
            }

            Node<T> added = new Node<T>(item);
            if (parent == null) {
                root = added;
            } else if (comparison < 0) {
                parent.left = added;
            } else {
                parent.right = added;
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
                } else if (parent.left == current) {
                    parent.left = replacement;
                } else {
                    parent.right = replacement;
                }
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
            int stackSize = 0;
            int index = 0;

            while (current != null || stackSize != 0) {
                while (current != null) {
                    stack[stackSize++] = current;
                    current = current.left;
                }
                current = stack[--stackSize];
                result[index++] = current.item;
                current = current.right;
            }
            return result;
        }
    }
}
