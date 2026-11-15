// Order interface to define the order for elements
interface Order<T> {
    int compare(T left, T right);
}

// TreeBag class implementing the required multiset functionalities
@TreeBag.ThreadSafe
class TreeBag<T> {
    @interface ThreadSafe {}

    private RedBlackNode<T> root;
    private int size = 0;
    private Order<T> order;

    public TreeBag(Order<T> order) {
        if (order == null) throw new NullPointerException("Order cannot be null");
        this.order = order;
    }

    // Helper class for Red-Black Tree nodes
    private static class RedBlackNode<T> {
        boolean isRed = true;
        T item;
        int count;
        RedBlackNode<T> left, right;

        public RedBlackNode(T item) {
            this.item = item;
            this.count = 1;
        }
    }

    // Helper method to perform left rotation
    private RedBlackNode<T> rotateLeft(RedBlackNode<T> node) {
        RedBlackNode<T> newRoot = node.right;
        node.right = newRoot.left;
        newRoot.left = node;
        newRoot.isRed = node.isRed;
        node.isRed = true;
        return newRoot;
    }

    // Helper method to perform right rotation
    private RedBlackNode<T> rotateRight(RedBlackNode<T> node) {
        RedBlackNode<T> newRoot = node.left;
        node.left = newRoot.right;
        newRoot.right = node;
        newRoot.isRed = node.isRed;
        node.isRed = true;
        return newRoot;
    }

    // Helper method to flip colors of a node and its children
    private void flipColors(RedBlackNode<T> node) {
        node.isRed = !node.isRed;
        node.left.isRed = !node.left.isRed;
        node.right.isRed = !node.right.isRed;
    }

    // Helper method to balance the tree
    private RedBlackNode<T> balance(RedBlackNode<T> node) {
        if (node.right.isRed && !node.left.isRed) node = rotateLeft(node);
        if (node.left.isRed && node.left.left.isRed) node = rotateRight(node);
        if (node.left.isRed && node.right.isRed) {
            flipColors(node);
        }
        return node;
    }

    // Insert an item into the tree
    public synchronized void add(T item) {
        root = insert(root, item);
        if (root.isRed) root.isRed = false;
        size++;
    }

    private RedBlackNode<T> insert(RedBlackNode<T> node, T item) {
        if (node == null) return new RedBlackNode<>(item);
        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = insert(node.left, item);
        } else if (cmp > 0) {
            node.right = insert(node.right, item);
        } else {
            node.count++;
        }
        return balance(node);
    }

    // Remove an occurrence of the item from the tree
    public synchronized boolean remove(T item) {
        if (root == null || !contains(root, item)) return false;
        root = delete(root, item);
        if (root != null) root.isRed = false;
        size--;
        return true;
    }

    private RedBlackNode<T> delete(RedBlackNode<T> node, T item) {
        if (node == null) return null;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) {
            node.left = delete(node.left, item);
        } else if (cmp > 0) {
            node.right = delete(node.right, item);
        } else {
            if (node.left == null) return node.right;
            if (node.right == null) return node.left;
            RedBlackNode<T> successor = min(node.right);
            node.item = successor.item;
            node.count = successor.count;
            node.right = delete(node.right, item);
        }
        return balance(node);
    }

    private boolean contains(RedBlackNode<T> node, T item) {
        if (node == null) return false;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) return contains(node.left, item);
        else if (cmp > 0) return contains(node.right, item);
        else return true;
    }

    private RedBlackNode<T> min(RedBlackNode<T> node) {
        if (node.left == null) return node;
        else return min(node.left);
    }

    // Get the element at the given index
    public synchronized T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return select(root, index);
    }

    private T select(RedBlackNode<T> node, int k) {
        int t = count(node.left);
        if (t > k) return select(node.left, k);
        else if (t < k) return select(node.right, k - t - 1);
        else return node.item;
    }

    // Get the rank of an item
    public synchronized int rank(T item) {
        return selectRank(root, item);
    }

    private int selectRank(RedBlackNode<T> node, T item) {
        if (node == null) return 0;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) return selectRank(node.left, item);
        else if (cmp > 0) return count(node.left) + selectRank(node.right, item) + 1;
        else return count(node.left);
    }

    // Get the size of the tree
    public synchronized int size() {
        return size;
    }

    // Get the distinct size of the tree
    public synchronized int distinctSize() {
        return distinctCount(root);
    }

    private int distinctCount(RedBlackNode<T> node) {
        if (node == null) return 0;
        return distinctCount(node.left) + distinctCount(node.right) + (node.count > 0 ? 1 : 0);
    }

    // Get the count of an item
    public synchronized int count(T item) {
        return selectCount(root, item);
    }

    private int selectCount(RedBlackNode<T> node, T item) {
        if (node == null) return 0;
        int cmp = order.compare(item, node.item);
        if (cmp < 0) return selectCount(node.left, item);
        else if (cmp > 0) return count(node.left) + selectCount(node.right, item);
        else return node.count;
    }

    // Snapshot of the tree
    public synchronized Snapshot<T> snapshot() {
        return new Snapshot<>(root, size);
    }

    // Snapshot class to capture a consistent state of the tree
    public static class Snapshot<T> {
        private RedBlackNode<T> root;
        private int size;

        public Snapshot(RedBlackNode<T> root, int size) {
            this.root = copy(root);
            this.size = size;
        }

        private RedBlackNode<T> copy(RedBlackNode<T> node) {
            if (node == null) return null;
            RedBlackNode<T> newNode = new RedBlackNode<>(node.item);
            newNode.count = node.count;
            newNode.isRed = node.isRed;
            newNode.left = copy(node.left);
            newNode.right = copy(node.right);
            return newNode;
        }

        public int size() {
            return size;
        }

        public int distinctSize() {
            return distinctCount(root);
        }

        private int distinctCount(RedBlackNode<T> node) {
            if (node == null) return 0;
            return distinctCount(node.left) + distinctCount(node.right) + (node.count > 0 ? 1 : 0);
        }

        public int count(T item) {
            return selectCount(root, item);
        }

        private int selectCount(RedBlackNode<T> node, T item) {
            if (node == null) return 0;
            int cmp = order.compare(item, node.item);
            if (cmp < 0) return selectCount(node.left, item);
            else if (cmp > 0) return count(node.left) + selectCount(node.right, item);
            else return node.count;
        }

        public T get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
            return select(root, index);
        }

        private T select(RedBlackNode<T> node, int k) {
            int t = count(node.left);
            if (t > k) return select(node.left, k);
            else if (t < k) return select(node.right, k - t - 1);
            else return node.item;
        }
    }
}
