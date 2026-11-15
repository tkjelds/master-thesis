interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static final class Node<E> {
        private E item;
        private Node<E> left;
        private Node<E> right;

        private Node(E item) {
            this.item = item;
        }
    }

    private final Object monitor = new Object();
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

        synchronized (monitor) {
            if (root == null) {
                root = new Node<T>(item);
                count++;
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
                        count++;
                        return true;
                    }
                    current = current.left;
                } else {
                    if (current.right == null) {
                        current.right = new Node<T>(item);
                        count++;
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
                parent = successorParent;
                current = successor;
            }

            Node<T> child = current.left != null ? current.left : current.right;
            if (parent == null) {
                root = child;
            } else if (parent.left == current) {
                parent.left = child;
            } else {
                parent.right = child;
            }

            count--;
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
        synchronized (monitor) {
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
        synchronized (monitor) {
            return count;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[count];
            Object[] stack = new Object[count];
            int top = 0;
            int index = 0;
            Node<T> current = root;

            while (current != null || top > 0) {
                while (current != null) {
                    stack[top++] = current;
                    current = current.left;
                }

                @SuppressWarnings("unchecked")
                Node<T> node = (Node<T>) stack[--top];
                result[index++] = node.item;
                current = node.right;
            }

            return result;
        }
    }
}
