interface Order<T> {
    int compare(T left, T right);
}

/**
 * A concurrent B-tree set. Operations acquire one intrinsic monitor; they do
 * not wait for elements or capacity. Empty first/last calls throw immediately
 * after acquiring the monitor. New insertions beyond Integer.MAX_VALUE
 * elements throw IllegalStateException.
 */
@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        final boolean leaf;
        int count;

        Node(int maximumKeys, boolean leaf) {
            this.keys = new Object[maximumKeys];
            this.children = leaf ? null : new Node[maximumKeys + 1];
            this.leaf = leaf;
        }
    }

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maximumKeys;
    private final Order<T> order;

    private Node root;
    private int elementCount;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2
                || minimumDegree > Integer.MAX_VALUE / 2) {
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
        synchronized (monitor) {
            requireItem(item);
            if (find(item)) {
                return false;
            }
            if (elementCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum set size reached");
            }

            if (root == null) {
                Node node = new Node(maximumKeys, true);
                node.keys[0] = item;
                node.count = 1;
                root = node;
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
        synchronized (monitor) {
            requireItem(item);
            if (!find(item)) {
                return false;
            }

            deleteExisting(root, item);
            elementCount--;

            if (root.count == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            requireItem(item);
            return find(item);
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return minimum(root);
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return maximum(root);
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

    private void requireItem(T item) {
        if (item == null) {
            throw new NullPointerException("item");
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
                    && order.compare(item, key(node, index)) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[index];
        }
        return false;
    }

    private void insertNonFull(Node node, T item) {
        while (!node.leaf) {
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
        Node right = new Node(maximumKeys, left.leaf);
        Object separator = left.keys[minimumDegree - 1];

        right.count = minimumDegree - 1;
        for (int i = 0; i < right.count; i++) {
            right.keys[i] = left.keys[i + minimumDegree];
            left.keys[i + minimumDegree] = null;
        }
        if (!left.leaf) {
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

    private void deleteExisting(Node node, T item) {
        int index = lowerBound(node, item);

        if (index < node.count
                && order.compare(item, key(node, index)) == 0) {
            if (node.leaf) {
                for (int i = index + 1; i < node.count; i++) {
                    node.keys[i - 1] = node.keys[i];
                }
                node.keys[--node.count] = null;
                return;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];

            if (left.count >= minimumDegree) {
                T predecessor = maximum(left);
                deleteExisting(left, predecessor);
                node.keys[index] = predecessor;
            } else if (right.count >= minimumDegree) {
                T successor = minimum(right);
                deleteExisting(right, successor);
                node.keys[index] = successor;
            } else {
                mergeChildren(node, index);
                deleteExisting(left, item);
            }
            return;
        }

        // The item is known to exist. Ensure its child can lose a key.
        if (node.children[index].count == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].count >= minimumDegree) {
                borrowPrevious(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowNext(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }

        deleteExisting(node.children[index], item);
    }

    private void borrowPrevious(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int i = child.count; i > 0; i--) {
            child.keys[i] = child.keys[i - 1];
        }
        if (!child.leaf) {
            for (int i = child.count + 1; i > 0; i--) {
                child.children[i] = child.children[i - 1];
            }
            child.children[0] = sibling.children[sibling.count];
            sibling.children[sibling.count] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.count - 1];
        sibling.keys[sibling.count - 1] = null;

        child.count++;
        sibling.count--;
    }

    private void borrowNext(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index + 1];

        child.keys[child.count] = parent.keys[index];
        if (!child.leaf) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int i = 1; i < sibling.count; i++) {
            sibling.keys[i - 1] = sibling.keys[i];
        }
        sibling.keys[sibling.count - 1] = null;

        if (!sibling.leaf) {
            for (int i = 1; i <= sibling.count; i++) {
                sibling.children[i - 1] = sibling.children[i];
            }
            sibling.children[sibling.count] = null;
        }

        child.count++;
        sibling.count--;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int offset = left.count + 1;

        left.keys[left.count] = parent.keys[index];
        for (int i = 0; i < right.count; i++) {
            left.keys[offset + i] = right.keys[i];
        }
        if (!left.leaf) {
            for (int i = 0; i <= right.count; i++) {
                left.children[offset + i] = right.children[i];
            }
        }
        left.count += right.count + 1;

        for (int i = index + 1; i < parent.count; i++) {
            parent.keys[i - 1] = parent.keys[i];
        }
        parent.keys[parent.count - 1] = null;

        for (int i = index + 2; i <= parent.count; i++) {
            parent.children[i - 1] = parent.children[i];
        }
        parent.children[parent.count] = null;
        parent.count--;
    }

    private T minimum(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return key(node, 0);
    }

    private T maximum(Node node) {
        while (!node.leaf) {
            node = node.children[node.count];
        }
        return key(node, node.count - 1);
    }

    private int copyInOrder(Node node, Object[] result, int offset) {
        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                offset = copyInOrder(node.children[i], result, offset);
            }
            result[offset++] = node.keys[i];
        }
        if (!node.leaf) {
            offset = copyInOrder(node.children[node.count], result, offset);
        }
        return offset;
    }
}
