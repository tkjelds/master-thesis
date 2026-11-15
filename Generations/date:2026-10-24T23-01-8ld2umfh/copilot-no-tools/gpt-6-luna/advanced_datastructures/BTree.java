@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        int keyCount;
        boolean leaf;

        Node(int maxKeys, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[maxKeys + 1];
            this.leaf = leaf;
        }
    }

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maxKeys;
    private final Order<T> order;
    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        this.minimumDegree = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (containsInternal(item)) {
                return false;
            }

            if (root == null) {
                root = new Node(maxKeys, true);
                root.keys[0] = item;
                root.keyCount = 1;
            } else {
                if (root.keyCount == maxKeys) {
                    Node newRoot = new Node(maxKeys, false);
                    newRoot.children[0] = root;
                    splitChild(newRoot, 0);
                    root = newRoot;
                }
                insertNonFull(root, item);
            }

            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (root == null || !removeFromNode(root, item)) {
                return false;
            }

            size--;
            if (root.keyCount == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return containsInternal(item);
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[0];
            }
            return keyAt(node, 0);
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[node.keyCount];
            }
            return keyAt(node, node.keyCount - 1);
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[size];
            if (root != null) {
                fillSnapshot(root, result, new int[1]);
            }
            return result;
        }
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private boolean containsInternal(T item) {
        Node node = root;
        while (node != null) {
            int index = lowerBound(node, item);
            if (index < node.keyCount && order.compare(keyAt(node, index), item) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[index];
        }
        return false;
    }

    private int lowerBound(Node node, T item) {
        int low = 0;
        int high = node.keyCount;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (order.compare(keyAt(node, middle), item) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private void insertNonFull(Node node, T item) {
        int index = lowerBound(node, item);
        if (node.leaf) {
            for (int i = node.keyCount; i > index; i--) {
                node.keys[i] = node.keys[i - 1];
            }
            node.keys[index] = item;
            node.keyCount++;
            return;
        }

        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            if (order.compare(item, keyAt(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node full = parent.children[index];
        Node right = new Node(maxKeys, full.leaf);
        int medianIndex = minimumDegree - 1;
        T median = keyAt(full, medianIndex);

        right.keyCount = minimumDegree - 1;
        for (int i = 0; i < right.keyCount; i++) {
            right.keys[i] = full.keys[i + minimumDegree];
            full.keys[i + minimumDegree] = null;
        }

        if (!full.leaf) {
            for (int i = 0; i < minimumDegree; i++) {
                right.children[i] = full.children[i + minimumDegree];
                full.children[i + minimumDegree] = null;
            }
        }

        full.keys[medianIndex] = null;
        full.keyCount = minimumDegree - 1;

        for (int i = parent.keyCount; i > index; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;

        for (int i = parent.keyCount - 1; i >= index; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[index] = median;
        parent.keyCount++;
    }

    private boolean removeFromNode(Node node, T item) {
        int index = lowerBound(node, item);

        if (index < node.keyCount && order.compare(keyAt(node, index), item) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
                return true;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.keyCount >= minimumDegree) {
                T predecessor = maximumKey(left);
                node.keys[index] = predecessor;
                return removeFromNode(left, predecessor);
            }
            if (right.keyCount >= minimumDegree) {
                T successor = minimumKey(right);
                node.keys[index] = successor;
                return removeFromNode(right, successor);
            }

            Node merged = mergeChildren(node, index);
            return removeFromNode(merged, item);
        }

        if (node.leaf) {
            return false;
        }

        if (node.children[index].keyCount == minimumDegree - 1) {
            fillChild(node, index);
        }

        int childIndex = index > node.keyCount ? index - 1 : index;
        return removeFromNode(node.children[childIndex], item);
    }

    private void fillChild(Node parent, int index) {
        if (index > 0
                && parent.children[index - 1].keyCount >= minimumDegree) {
            borrowFromPrevious(parent, index);
        } else if (index < parent.keyCount
                && parent.children[index + 1].keyCount >= minimumDegree) {
            borrowFromNext(parent, index);
        } else if (index < parent.keyCount) {
            mergeChildren(parent, index);
        } else {
            mergeChildren(parent, index - 1);
        }
    }

    private void borrowFromPrevious(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int i = child.keyCount - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (!child.leaf) {
            for (int i = child.keyCount; i >= 0; i--) {
                child.children[i + 1] = child.children[i];
            }
            child.children[0] = sibling.children[sibling.keyCount];
            sibling.children[sibling.keyCount] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.keyCount - 1];
        sibling.keys[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void borrowFromNext(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index + 1];

        child.keys[child.keyCount] = parent.keys[index];
        if (!child.leaf) {
            child.children[child.keyCount + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int i = 0; i < sibling.keyCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;

        if (!sibling.leaf) {
            for (int i = 0; i < sibling.keyCount; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.keyCount] = null;
        }

        child.keyCount++;
        sibling.keyCount--;
    }

    private Node mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int leftCount = left.keyCount;
        int rightCount = right.keyCount;

        left.keys[leftCount] = parent.keys[index];
        for (int i = 0; i < rightCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            right.keys[i] = null;
        }

        if (!left.leaf) {
            for (int i = 0; i <= rightCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }
        left.keyCount = leftCount + rightCount + 1;

        int oldParentCount = parent.keyCount;
        for (int i = index; i < oldParentCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[oldParentCount - 1] = null;

        for (int i = index + 1; i < oldParentCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[oldParentCount] = null;
        parent.keyCount--;

        return left;
    }

    private void removeKeyAt(Node node, int index) {
        for (int i = index; i < node.keyCount - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
    }

    private T minimumKey(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return keyAt(node, 0);
    }

    private T maximumKey(Node node) {
        while (!node.leaf) {
            node = node.children[node.keyCount];
        }
        return keyAt(node, node.keyCount - 1);
    }

    private void fillSnapshot(Node node, Object[] result, int[] index) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                fillSnapshot(node.children[i], result, index);
            }
            result[index[0]++] = node.keys[i];
        }
        if (!node.leaf) {
            fillSnapshot(node.children[node.keyCount], result, index);
        }
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }
}
