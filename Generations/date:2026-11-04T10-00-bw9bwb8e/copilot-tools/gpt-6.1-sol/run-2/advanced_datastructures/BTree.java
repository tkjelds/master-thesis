@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maximumKeys;
    private final int maximumChildren;
    private final Order<T> order;

    private Node root;
    private int elementCount;

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        final boolean leaf;
        int count;

        Node(boolean leaf, int maximumKeys, int maximumChildren) {
            this.leaf = leaf;
            this.keys = new Object[maximumKeys];
            this.children = new Node[maximumChildren];
        }
    }

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2
                || 2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        this.minimumDegree = minimumDegree;
        this.maximumChildren = minimumDegree * 2;
        this.maximumKeys = maximumChildren - 1;
        this.order = order;
    }

    public boolean add(T item) {
        synchronized (monitor) {
            requireItem(item);
            if (containsInternal(item)) {
                return false;
            }
            if (elementCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Maximum set size reached");
            }

            if (root == null) {
                root = newNode(true);
            } else if (root.count == maximumKeys) {
                Node newRoot = newNode(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }

            insertNonFull(root, item);
            elementCount++;
            return true;
        }
    }

    public boolean remove(T item) {
        synchronized (monitor) {
            requireItem(item);
            if (!containsInternal(item)) {
                return false;
            }

            delete(root, item);
            if (root.count == 0) {
                root = root.leaf ? null : root.children[0];
            }
            elementCount--;
            return true;
        }
    }

    public boolean contains(T item) {
        synchronized (monitor) {
            requireItem(item);
            return containsInternal(item);
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

    private Node newNode(boolean leaf) {
        return new Node(leaf, maximumKeys, maximumChildren);
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

    private boolean containsInternal(T item) {
        Node node = root;
        while (node != null) {
            int index = lowerBound(node, item);
            if (index < node.count
                    && order.compare(key(node, index), item) == 0) {
                return true;
            }
            if (node.leaf) {
                return false;
            }
            node = node.children[index];
        }
        return false;
    }

    private void insertNonFull(Node node, T item) {
        int index = lowerBound(node, item);

        if (node.leaf) {
            for (int i = node.count; i > index; i--) {
                node.keys[i] = node.keys[i - 1];
            }
            node.keys[index] = item;
            node.count++;
            return;
        }

        if (node.children[index].count == maximumKeys) {
            splitChild(node, index);
            if (order.compare(item, key(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = newNode(left.leaf);
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

    private void delete(Node node, T item) {
        int index = lowerBound(node, item);

        if (index < node.count
                && order.compare(key(node, index), item) == 0) {
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
                node.keys[index] = predecessor;
                delete(left, predecessor);
            } else if (right.count >= minimumDegree) {
                T successor = minimum(right);
                node.keys[index] = successor;
                delete(right, successor);
            } else {
                mergeChildren(node, index);
                delete(left, item);
            }
            return;
        }

        // remove has already established that the item exists.
        if (node.children[index].count == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].count >= minimumDegree) {
                borrowFromPrevious(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowFromNext(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }

        delete(node.children[index], item);
    }

    private void borrowFromPrevious(Node parent, int index) {
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

        sibling.count--;
        child.count++;
    }

    private void borrowFromNext(Node parent, int index) {
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
        if (!left.leaf) {
            for (int i = 0; i <= right.count; i++) {
                left.children[offset + i] = right.children[i];
            }
        }
        left.count += right.count + 1;

        for (int i = index + 1; i < parent.count; i++) {
            parent.keys[i - 1] = parent.keys[i];
        }
        for (int i = index + 2; i <= parent.count; i++) {
            parent.children[i - 1] = parent.children[i];
        }

        parent.keys[parent.count - 1] = null;
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

    private int copyInOrder(Node node, Object[] destination, int offset) {
        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                offset = copyInOrder(node.children[i], destination, offset);
            }
            destination[offset++] = node.keys[i];
        }
        if (!node.leaf) {
            offset = copyInOrder(node.children[node.count], destination, offset);
        }
        return offset;
    }
}

interface Order<T> {
    int compare(T left, T right);
}
