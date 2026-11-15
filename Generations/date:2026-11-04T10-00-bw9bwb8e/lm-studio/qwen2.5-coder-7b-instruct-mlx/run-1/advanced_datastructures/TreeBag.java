import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

interface Order<T> {
    int compare(T left, T right);
}

@Snapshot.ThreadSafe
class Snapshot<T> {
    @interface ThreadSafe {}

    private final TreeBag<T> treeBag;

    public Snapshot(TreeBag<T> treeBag) {
        this.treeBag = treeBag;
    }

    int size() { return treeBag.size(); }
    int distinctSize() { return treeBag.distinctSize(); }
    int count(T item) { return treeBag.count(item); }
    T get(int index) { return treeBag.get(index); }
}

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private Node root = null;
    private int size = 0;
    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    public TreeBag(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    void add(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            root = add(root, item);
        } finally {
            lock.unlock();
        }
    }

    private Node add(Node node, T item) {
        if (node == null) return new Node(item);
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            node.right = add(node.right, item);
        } else if (cmp > 0) {
            node.left = add(node.left, item);
        } else {
            node.count++;
        }
        return balance(node);
    }

    boolean remove(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            return remove(root, item);
        } finally {
            lock.unlock();
        }
    }

    private boolean remove(Node node, T item) {
        if (node == null) return false;
        int cmp = order.compare(node.item, item);
        if (cmp < 0) {
            node.right = remove(node.right, item);
        } else if (cmp > 0) {
            node.left = remove(node.left, item);
        } else {
            if (--node.count == 0) {
                if (node.left != null && node.right != null) {
                    Node successor = node.right;
                    while (successor.left != null) successor = successor.left;
                    node.item = successor.item;
                    node.right = remove(node.right, successor.item);
                } else {
                    node = (node.left != null) ? node.left : node.right;
                }
            }
        }
        return true;
    }

    int count(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            return count(root, item);
        } finally {
            lock.unlock();
        }
    }

    private int count(Node node, T item) {
        if (node == null) return 0;
        int cmp = order.compare(node.item, item);
        if (cmp < 0) return count(node.right, item);
        else if (cmp > 0) return count(node.left, item);
        else return node.count;
    }

    int size() {
        lock.lock();
        try {
            return this.size;
        } finally {
            lock.unlock();
        }
    }

    int distinctSize() {
        lock.lock();
        try {
            return countDistinct(root);
        } finally {
            lock.unlock();
        }
    }

    private int countDistinct(Node node) {
        if (node == null) return 0;
        return countDistinct(node.left) + countDistinct(node.right) + 1;
    }

    T get(int index) {
        lock.lock();
        try {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            return get(root, index);
        } finally {
            lock.unlock();
        }
    }

    private T get(Node node, int index) {
        if (node.left != null) {
            int leftSize = size(node.left);
            if (index < leftSize) return get(node.left, index);
            else if (index == leftSize) return node.item;
            else return get(node.right, index - leftSize - 1);
        } else {
            if (index == 0) return node.item;
            else return get(node.right, index - 1);
        }
    }

    int rank(T item) {
        lock.lock();
        try {
            if (item == null) throw new NullPointerException();
            return rank(root, item);
        } finally {
            lock.unlock();
        }
    }

    private int rank(Node node, T item) {
        if (node == null) return 0;
        int cmp = order.compare(node.item, item);
        if (cmp < 0) return rank(node.right, item) + size(node.left) + node.count;
        else if (cmp > 0) return rank(node.left, item);
        else return size(node.left);
    }

    Snapshot<T> snapshot() {
        lock.lock();
        try {
            return new Snapshot<>(this);
        } finally {
            lock.unlock();
        }
    }

    private int size(Node node) {
        return (node == null) ? 0 : node.count;
    }

    private Node balance(Node node) {
        if (node == null) return null;
        int leftHeight = height(node.left);
        int rightHeight = height(node.right);
        if (leftHeight > rightHeight + 1) {
            int leftLeftHeight = height(node.left.left);
            int leftRightHeight = height(node.left.right);
            if (leftRightHeight > leftLeftHeight) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        } else if (rightHeight > leftHeight + 1) {
            int rightLeftHeight = height(node.right.left);
            int rightRightHeight = height(node.right.right);
            if (rightLeftHeight > rightRightHeight) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        } else {
            node.height = Math.max(leftHeight, rightHeight) + 1;
            return node;
        }
    }

    private Node rotateLeft(Node node) {
        Node right = node.right;
        node.right = right.left;
        right.left = node;
        node.height = Math.max(height(node.left), height(node.right)) + 1;
        right.height = Math.max(height(right.left), height(right.right)) + 1;
        return right;
    }

    private Node rotateRight(Node node) {
        Node left = node.left;
        node.left = left.right;
        left.right = node;
        node.height = Math.max(height(node.left), height(node.right)) + 1;
        left.height = Math.max(height(left.left), height(left.right)) + 1;
        return left;
    }

    private int height(Node node) {
        return (node == null) ? 0 : node.height;
    }

    static class Node {
        final T item;
        int count = 1;
        int height = 1;
        Node left, right;

        Node(T item) {
            this.item = item;
        }
    }
}
