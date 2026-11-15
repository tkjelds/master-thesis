interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
public class BinarySearchTree<T> {
    @interface ThreadSafe {}


    private static final class Node<T> {
        T element;
        Node<T> left;
        Node<T> right;

        Node(T element) {
            this.element = element;
        }
    }

    private final Order<T> order;
    private final Object lock = new Object();
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
                int cmp = order.compare(item, current.element);
                if (cmp == 0) {
                    return false;
                }
                if (cmp < 0) {
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
            boolean[] removed = new boolean[1];
            root = removeFrom(root, item, removed);
            if (removed[0]) {
                size--;
            }
            return removed[0];
        }
    }

    private Node<T> removeFrom(Node<T> node, T item, boolean[] removed) {
        if (node == null) {
            return null;
        }
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            node.left = removeFrom(node.left, item, removed);
            return node;
        }
        if (cmp > 0) {
            node.right = removeFrom(node.right, item, removed);
            return node;
        }
        removed[0] = true;
        if (node.left == null) {
            return node.right;
        }
        if (node.right == null) {
            return node.left;
        }
        Node<T> successor = node.right;
        while (successor.left != null) {
            successor = successor.left;
        }
        node.element = successor.element;
        node.right = removeMin(node.right);
        return node;
    }

    private Node<T> removeMin(Node<T> node) {
        if (node.left == null) {
            return node.right;
        }
        node.left = removeMin(node.left);
        return node;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            Node<T> current = root;
            while (current != null) {
                int cmp = order.compare(item, current.element);
                if (cmp == 0) {
                    return true;
                }
                current = cmp < 0 ? current.left : current.right;
            }
            return false;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> current = root;
            while (current.left != null) {
                current = current.left;
            }
            return current.element;
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }
            Node<T> current = root;
            while (current.right != null) {
                current = current.right;
            }
            return current.element;
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
            int[] index = new int[1];
            fillInOrder(root, result, index);
            return result;
        }
    }

    private void fillInOrder(Node<T> node, Object[] out, int[] index) {
        if (node == null) {
            return;
        }
        fillInOrder(node.left, out, index);
        out[index[0]++] = node.element;
        fillInOrder(node.right, out, index);
    }
}
