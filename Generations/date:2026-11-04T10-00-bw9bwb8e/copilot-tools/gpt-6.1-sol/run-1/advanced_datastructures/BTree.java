/**
 * A concurrent B-tree set. All tree access is protected by one monitor.
 * Operations never wait for elements or capacity; empty first/last calls throw.
 */
@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final Order<T> order;
    private final int minimumDegree;
    private final int maximumKeys;

    private Node root;
    private int elementCount;

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        int count;

        Node(int maximumKeys, boolean leaf) {
            keys = new Object[maximumKeys];
            children = leaf ? null : new Node[maximumKeys + 1];
        }

        boolean isLeaf() {
            return children == null;
        }
    }

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2
                || 2L * minimumDegree > 2147483647L) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        this.minimumDegree = minimumDegree;
        this.maximumKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (find(item)) {
                return false;
            }
            if (elementCount == 2147483647) {
                throw new IllegalStateException("Maximum supported size reached");
            }

            if (root == null) {
                Node leaf = new Node(maximumKeys, true);
                leaf.keys[0] = item;
                leaf.count = 1;
                root = leaf;
            } else {
                if (root.count == maximumKeys) {
                    Node newRoot = new Node(maximumKeys, false);
                    newRoot.children[0] = root;
                    splitChild(newRoot, 0);
                    root = newRoot;
                }
                insertNonFull(root, item);
            }

            elementCount++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (!find(item)) {
                return false;
            }

            delete(root, item);
            if (root.count == 0) {
                root = root.isLeaf() ? null : root.children[0];
            }
            elementCount--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            return find(item);
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return smallest(root);
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return largest(root);
        }
    }

    public int size() {
        synchronized (monitor) {
            return elementCount;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[elementCount];
            if (root != null) {
                copyInOrder(root, result, 0);
            }
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private T key(Node node, int index) {
        return (T) node.keys[index];
    }

    private int lowerBound(Node node, T item) {
        int low = 0;
        int high = node.count;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (order.compare(key(node, middle), item) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private boolean find(T item) {
        Node node = root;
        while (node != null) {
            int index = lowerBound(node, item);
            if (index < node.count
                    && order.compare(key(node, index), item) == 0) {
                return true;
            }
            node = node.isLeaf() ? null : node.children[index];
        }
        return false;
    }

    private void insertNonFull(Node node, T item) {
        while (!node.isLeaf()) {
            int index = lowerBound(node, item);
            if (node.children[index].count == maximumKeys) {
                splitChild(node, index);
                if (order.compare(item, key(node, index)) > 0) {
                    index++;
                }
            }
            node = node.children[index];
        }

        int index = lowerBound(node, item);
        for (int i = node.count; i > index; i--) {
            node.keys[i] = node.keys[i - 1];
        }
        node.keys[index] = item;
        node.count++;
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = new Node(maximumKeys, left.isLeaf());
        Object separator = left.keys[minimumDegree - 1];

        right.count = minimumDegree - 1;
        for (int i = 0; i < right.count; i++) {
            right.keys[i] = left.keys[i + minimumDegree];
            left.keys[i + minimumDegree] = null;
        }

        if (!left.isLeaf()) {
            for (int i = 0; i < minimumDegree; i++) {
                right.children[i] = left.children[i + minimumDegree];
                left.children[i + minimumDegree] = null;
            }
        }

        left.keys[minimumDegree - 1] = null;
        left.count = minimumDegree - 1;

        for (int i = parent.count; i > index; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;

        for (int i = parent.count; i > index; i--) {
            parent.keys[i] = parent.keys[i - 1];
        }
        parent.keys[index] = separator;
        parent.count++;
    }

    private void delete(Node node, T item) {
        int index = lowerBound(node, item);

        if (index < node.count
                && order.compare(key(node, index), item) == 0) {
            if (node.isLeaf()) {
                removeLeafKey(node, index);
            } else {
                Node left = node.children[index];
                Node right = node.children[index + 1];

                if (left.count >= minimumDegree) {
                    T predecessor = largest(left);
                    node.keys[index] = predecessor;
                    delete(left, predecessor);
                } else if (right.count >= minimumDegree) {
                    T successor = smallest(right);
                    node.keys[index] = successor;
                    delete(right, successor);
                } else {
                    mergeChildren(node, index);
                    delete(left, item);
                }
            }
            return;
        }

        // The public remove method established that the key exists.
        Node child = node.children[index];
        if (child.count == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].count >= minimumDegree) {
                borrowFromLeft(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowFromRight(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }

        delete(node.children[index], item);
    }

    private void removeLeafKey(Node node, int index) {
        for (int i = index; i < node.count - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[--node.count] = null;
    }

    private void borrowFromLeft(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int i = child.count; i > 0; i--) {
            child.keys[i] = child.keys[i - 1];
        }
        if (!child.isLeaf()) {
            for (int i = child.count + 1; i > 0; i--) {
                child.children[i] = child.children[i - 1];
            }
            child.children[0] = sibling.children[sibling.count];
            sibling.children[sibling.count] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.count - 1];
        sibling.keys[sibling.count - 1] = null;
        sibling.count--;
        child.count++;
    }

    private void borrowFromRight(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index + 1];

        child.keys[child.count] = parent.keys[index];
        if (!child.isLeaf()) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int i = 0; i < sibling.count - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.count - 1] = null;

        if (!sibling.isLeaf()) {
            for (int i = 0; i < sibling.count; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.count] = null;
        }

        sibling.count--;
        child.count++;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int offset = left.count + 1;

        left.keys[left.count] = parent.keys[index];
        for (int i = 0; i < right.count; i++) {
            left.keys[offset + i] = right.keys[i];
        }
        if (!left.isLeaf()) {
            for (int i = 0; i <= right.count; i++) {
                left.children[offset + i] = right.children[i];
            }
        }
        left.count += right.count + 1;

        for (int i = index; i < parent.count - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[parent.count - 1] = null;

        for (int i = index + 1; i < parent.count; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[parent.count] = null;
        parent.count--;
    }

    private T smallest(Node node) {
        while (!node.isLeaf()) {
            node = node.children[0];
        }
        return key(node, 0);
    }

    private T largest(Node node) {
        while (!node.isLeaf()) {
            node = node.children[node.count];
        }
        return key(node, node.count - 1);
    }

    private int copyInOrder(Node node, Object[] result, int offset) {
        for (int i = 0; i < node.count; i++) {
            if (!node.isLeaf()) {
                offset = copyInOrder(node.children[i], result, offset);
            }
            result[offset++] = node.keys[i];
        }
        if (!node.isLeaf()) {
            offset = copyInOrder(node.children[node.count], result, offset);
        }
        return offset;
    }
}

interface Order<T> {
    int compare(T left, T right);
}
