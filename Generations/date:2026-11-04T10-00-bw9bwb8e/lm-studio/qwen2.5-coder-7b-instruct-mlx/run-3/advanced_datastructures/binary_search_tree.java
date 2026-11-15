import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@BinarySearchTree.ThreadSafe
class BinarySearchTree<T> {
    @interface ThreadSafe {}

    private static class Node<T> {
        T element;
        Node<T> left, right;
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();

        Node(T element) {
            this.element = element;
        }
    }

    private final Order<T> order;
    private Node<T> root = null;

    public BinarySearchTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    public synchronized boolean add(T item) {
        if (item == null) throw new NullPointerException();
        return recursiveAdd(root, item);
    }

    private boolean recursiveAdd(Node<T> node, T item) {
        if (node == null) {
            this.root = new Node<>(item);
            return true;
        }
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            synchronized (node.lock) {
                return recursiveAdd(node.left, item);
            }
        } else if (cmp > 0) {
            synchronized (node.lock) {
                return recursiveAdd(node.right, item);
            }
        } else {
            return false;
        }
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        return recursiveRemove(root, item);
    }

    private boolean recursiveRemove(Node<T> node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            synchronized (node.lock) {
                return recursiveRemove(node.left, item);
            }
        } else if (cmp > 0) {
            synchronized (node.lock) {
                return recursiveRemove(node.right, item);
            }
        } else {
            removeNode(node);
            return true;
        }
    }

    private void removeNode(Node<T> node) {
        if (node.left == null && node.right == null) {
            node = null;
        } else if (node.left == null) {
            node = node.right;
        } else if (node.right == null) {
            node = node.left;
        } else {
            Node<T> successor = getSuccessor(node);
            node.element = successor.element;
            removeNode(successor);
        }
    }

    private Node<T> getSuccessor(Node<T> node) {
        synchronized (node.lock) {
            Node<T> current = node.right;
            while (current.left != null) {
                synchronized (current.lock) {
                    current = current.left;
                }
            }
            return current;
        }
    }

    public synchronized boolean contains(T item) {
        if (item == null) throw new NullPointerException();
        return recursiveContains(root, item);
    }

    private boolean recursiveContains(Node<T> node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.element);
        if (cmp < 0) {
            return recursiveContains(node.left, item);
        } else if (cmp > 0) {
            return recursiveContains(node.right, item);
        } else {
            return true;
        }
    }

    public synchronized T first() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.left != null) {
            synchronized (current.lock) {
                current = current.left;
            }
        }
        return current.element;
    }

    public synchronized T last() {
        if (root == null) throw new IllegalStateException();
        Node<T> current = root;
        while (current.right != null) {
            synchronized (current.lock) {
                current = current.right;
            }
        }
        return current.element;
    }

    public synchronized int size() {
        return recursiveSize(root);
    }

    private int recursiveSize(Node<T> node) {
        if (node == null) return 0;
        return recursiveSize(node.left) + 1 + recursiveSize(node.right);
    }

    public synchronized Object[] snapshot() {
        Object[] array = new Object[size()];
        recursiveSnapshot(root, array, 0);
        return array;
    }

    private int recursiveSnapshot(Node<T> node, Object[] array, int index) {
        if (node == null) return index;
        index = recursiveSnapshot(node.left, array, index);
        array[index++] = node.element;
        return recursiveSnapshot(node.right, array, index);
    }
}
