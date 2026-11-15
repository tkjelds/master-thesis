@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private Node root;
    private int size;

    private final class Node {
        private final T item;
        private Node left;
        private Node right;

        private Node(T item) {
            this.item = item;
        }
    }

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

        synchronized (lock) {
            if (root == null) {
                root = new Node(item);
                size++;
                return true;
            }

            Node parent = null;
            Node current = root;
            int comparison = 0;
            while (current != null) {
                parent = current;
                comparison = order.compare(item, current.item);
                if (comparison == 0) {
                    return false;
                }
                current = comparison < 0 ? current.left : current.right;
            }

            Node added = new Node(item);
            if (comparison < 0) {
                parent.left = added;
            } else {
                parent.right = added;
            }
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node parent = null;
            Node target = root;

            while (target != null) {
                int comparison = order.compare(item, target.item);
                if (comparison == 0) {
                    break;
                }
                parent = target;
                target = comparison < 0 ? target.left : target.right;
            }

            if (target == null) {
                return false;
            }

            Node replacement;
            if (target.left == null) {
                replacement = target.right;
            } else if (target.right == null) {
                replacement = target.left;
            } else {
                Node successorParent = target;
                Node successor = target.right;
                while (successor.left != null) {
                    successorParent = successor;
                    successor = successor.left;
                }

                if (successorParent != target) {
                    successorParent.left = successor.right;
                    successor.right = target.right;
                }
                successor.left = target.left;
                replacement = successor;
            }

            if (parent == null) {
                root = replacement;
            } else if (parent.left == target) {
                parent.left = replacement;
            } else {
                parent.right = replacement;
            }

            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException();
        }

        synchronized (lock) {
            Node current = root;
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
                throw new IllegalStateException();
            }

            Node current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.item;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException();
            }

            Node current = root;
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
            Object[] stack = new Object[size];
            int top = 0;
            int index = 0;
            Node current = root;

            while (current != null || top > 0) {
                while (current != null) {
                    stack[top++] = current;
                    current = current.left;
                }

                current = (Node) stack[--top];
                stack[top] = null;
                result[index++] = current.item;
                current = current.right;
            }

            return result;
        }
    }
}
