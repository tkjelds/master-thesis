import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@RedBlackTree.ThreadSafe
class RedBlackTree<T> {
    @interface ThreadSafe {}

    private static final boolean BLACK = false;
    private static final boolean RED = true;

    private class Node {
        T item;
        Node left, right;
        boolean color;

        Node(T item) {
            this.item = item;
            left = right = null;
            color = RED;
        }
    }

    private Node root;
    private Order<T> order;
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    public RedBlackTree(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    public boolean add(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            root = add(root, item);
            root.color = BLACK;
            return true;
        } finally {
            lock.unlock();
        }
    }

    private Node add(Node node, T item) {
        if (node == null) return new Node(item);
        int cmp = order.compare(node.item, item);
        if (cmp < 0) node.right = add(node.right, item);
        else if (cmp > 0) node.left = add(node.left, item);

        return fixup(node);
    }

    private Node fixup(Node node) {
        // Implement your fixup logic here
        return node;
    }

    public boolean remove(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            boolean result = remove(root, item);
            if (result && root != null) root.color = BLACK;
            return result;
        } finally {
            lock.unlock();
        }
    }

    private boolean remove(Node node, T item) {
        if (node == null) return false;
        int cmp = order.compare(node.item, item);
        if (cmp < 0) return remove(node.right, item);
        else if (cmp > 0) return remove(node.left, item);

        Node y = node;
        boolean wasRed = y.color == RED;

        if (node.left == null) {
            node = node.right;
        } else if (node.right == null) {
            node = node.left;
        } else {
            Node x = minimum(node.right);
            y.item = x.item;
            node.right = removeMin(node.right);
        }

        if (node != null) node.color = wasRed;

        return true;
    }

    private Node minimum(Node node) {
        if (node.left == null) return node;
        return minimum(node.left);
    }

    private Node removeMin(Node node) {
        if (node.left == null) return null;
        if (!isRed(node.left) && !isRed(node.left.left)) node = moveRedLeft(node);
        node.left = removeMin(node.left);
        return fixup(node);
    }

    private boolean isRed(Node node) {
        return node != null && node.color == RED;
    }

    private Node moveRedLeft(Node node) {
        // Implement your logic here
        return node;
    }

    public boolean contains(T item) {
        lock.lock();
        try {
            return find(root, item) != null;
        } finally {
            lock.unlock();
        }
    }

    private Node find(Node node, T item) {
        if (node == null) return null;
        int cmp = order.compare(node.item, item);
        if (cmp < 0) return find(node.right, item);
        else if (cmp > 0) return find(node.left, item);
        else return node;
    }

    public T first() {
        lock.lock();
        try {
            if (root == null) throw new IllegalStateException();
            return minimum(root).item;
        } finally {
            lock.unlock();
        }
    }

    public T last() {
        lock.lock();
        try {
            if (root == null) throw new IllegalStateException();
            return maximum(root).item;
        } finally {
            lock.unlock();
        }
    }

    private Node maximum(Node node) {
        while (node.right != null) node = node.right;
        return node;
    }

    public int size() {
        lock.lock();
        try {
            return size(root);
        } finally {
            lock.unlock();
        }
    }

    private int size(Node node) {
        if (node == null) return 0;
        return 1 + size(node.left) + size(node.right);
    }

    public Object[] snapshot() {
        lock.lock();
        try {
            return toArray(root);
        } finally {
            lock.unlock();
        }
    }

    private Object[] toArray(Node node) {
        if (node == null) return new Object[0];
        Object[] left = toArray(node.left);
        Object[] right = toArray(node.right);
        Object[] result = new Object[left.length + 1 + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        result[left.length] = node.item;
        System.arraycopy(right, 0, result, left.length + 1, right.length);
        return result;
    }
}
