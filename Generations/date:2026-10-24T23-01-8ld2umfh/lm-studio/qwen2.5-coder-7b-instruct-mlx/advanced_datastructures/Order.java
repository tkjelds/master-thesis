
import java.util.NoSuchElementException;

@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private static final int MAX_COUNT = Integer.MAX_VALUE - 1;
    private Node<T> root;
    private Order<T> order;

    public TreeBag(Order<T> order) {
        if (order == null) throw new NullPointerException();
        this.order = order;
    }

    public synchronized void add(T item) {
        if (item == null) throw new NullPointerException();
        root = add(root, item, 1);
    }

    private Node<T> add(Node<T> node, T item, int count) {
        if (node == null) return new Node<>(item, count);
        int cmp = order.compare(item, node.value);
        if (cmp < 0) node.left = add(node.left, item, count);
        else if (cmp > 0) node.right = add(node.right, item, count);
        else {
            if (node.count + count > MAX_COUNT) throw new IllegalStateException("Overflow");
            node.count += count;
        }
        updateHeightAndCount(node);
        return balance(node);
    }

    public synchronized boolean remove(T item) {
        if (item == null) throw new NullPointerException();
        boolean result = remove(root, item, 1);
        if (result) root = balance(root);
        return result;
    }

    private boolean remove(Node<T> node, T item, int count) {
        if (node == null) return false;
        int cmp = order.compare(item, node.value);
        if (cmp < 0) return remove(node.left, item, count);
        else if (cmp > 0) return remove(node.right, item, count);
        else {
            if (count < node.count) {
                node.count -= count;
                return true;
            } else if (count == node.count) {
                node = removeRoot(node);
                return true;
            }
        }
        updateHeightAndCount(node);
        return false;
    }

    private Node<T> removeRoot(Node<T> node) {
        if (node.left == null) return node.right;
        else if (node.right == null) return node.left;
        else {
            Node<T> successor = findMinNode(node.right);
            node.value = successor.value;
            node.count = successor.count;
            node.right = remove(node.right, successor.value, 1);
        }
        return balance(node);
    }

    public synchronized int count(T item) {
        if (item == null) throw new NullPointerException();
        return findNode(root, item).count;
    }

    public synchronized int size() {
        return root != null ? root.count : 0;
    }

    public synchronized int distinctSize() {
        return root != null ? root.size : 0;
    }

    public synchronized T get(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
        return findNodeAt(root, index).value;
    }

    public synchronized int rank(T item) {
        if (item == null) throw new NullPointerException();
        Node<T> node = findNode(root, item);
        int rank = 0;
        for (Node<T> current = root; current != null;) {
            int cmp = order.compare(item, current.value);
            if (cmp < 0) current = current.left;
            else if (cmp > 0) {
                rank += (current.left != null ? current.left.count : 0);
                current = current.right;
            } else {
                rank += (current.left != null ? current.left.count : 0);
                break;
            }
        }
        return rank + (node != null ? node.count : 0);
    }

    public synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(root, order);
    }

    private Node<T> findMinNode(Node<T> node) {
        while (node.left != null) node = node.left;
        return node;
    }

    private Node<T> findNodeAt(Node<T> node, int index) {
        int leftCount = (node.left != null ? node.left.count : 0);
        if (index < leftCount) return findNodeAt(node.left, index);
        else if (index > leftCount + node.count) return findNodeAt(node.right, index - leftCount - node.count);
        else return node;
    }

    private Node<T> findNode(Node<T> node, T item) {
        while (node != null) {
            int cmp = order.compare(item, node.value);
            if (cmp < 0) node = node.left;
            else if (cmp > 0) node = node.right;
            else return node;
        }
        return null;
    }

    private Node<T> balance(Node<T> node) {
        updateHeightAndCount(node);
        if (getBalanceFactor(node) > 1) {
            if (getBalanceFactor(node.left) < 0) node.left = rotateLeft(node.left);
            return rotateRight(node);
        } else if (getBalanceFactor(node) < -1) {
            if (getBalanceFactor(node.right) > 0) node.right = rotateRight(node.right);
            return rotateLeft(node);
        }
        return node;
    }

    private Node<T> rotateRight(Node<T> node) {
        Node<T> newRoot = node.left;
        node.left = newRoot.right;
        newRoot.right = node;
        updateHeightAndCount(node);
        updateHeightAndCount(newRoot);
        return newRoot;
    }

    private Node<T> rotateLeft(Node<T> node) {
        Node<T> newRoot = node.right;
        node.right = newRoot.left;
        newRoot.left = node;
        updateHeightAndCount(node);
        updateHeightAndCount(newRoot);
        return newRoot;
    }

    private int getHeight(Node<T> node) {
        return (node != null ? node.height : 0);
    }

    private int getBalanceFactor(Node<T> node) {
        return getHeight(node.left) - getHeight(node.right);
    }

    private void updateHeightAndCount(Node<T> node) {
        int leftHeight = getHeight(node.left);
        int rightHeight = getHeight(node.right);
        node.height = 1 + Math.max(leftHeight, rightHeight);
        if (node.left != null) node.size = 1 + leftHeight + node.count;
        else if (node.right != null) node.size = 1 + rightHeight + node.count;
        else node.size = 1;
    }

    private final class Node<T> {
        T value;
        int count;
        int size;
        int height;
        Node<T> left, right;

        Node(T value, int count) {
            this.value = value;
            this.count = count;
            this.size = 1;
            this.height = 1;
        }
    }

    public interface Order<T> {
        int compare(T left, T right);
    }

    public final class Snapshot<T> {
        private final Node<T> rootSnapshot;
        private final Order<T> order;

        Snapshot(Node<T> root, Order<T> order) {
            this.rootSnapshot = copy(root);
            this.order = order;
        }

        private Node<T> copy(Node<T> node) {
            if (node == null) return null;
            Node<T> newNode = new Node<>(node.value, node.count);
            newNode.left = copy(node.left);
            newNode.right = copy(node.right);
            updateHeightAndCount(newNode);
            return newNode;
        }

        public int size() {
            return rootSnapshot != null ? rootSnapshot.count : 0;
        }

        public int distinctSize() {
            return rootSnapshot != null ? rootSnapshot.size : 0;
        }

        public int count(T item) {
            if (item == null) throw new NullPointerException();
            Node<T> node = findNode(rootSnapshot, item);
            return (node != null ? node.count : 0);
        }

        public T get(int index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            return findNodeAt(rootSnapshot, index).value;
        }
    }
}
