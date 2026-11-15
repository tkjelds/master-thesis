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

        Node(int maxKeys, int maxChildren, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[maxChildren];
            this.leaf = leaf;
        }
    }

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maxKeys;
    private final int maxChildren;
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
        this.maxChildren = 2 * minimumDegree;
        this.order = order;
        this.root = new Node(maxKeys, maxChildren, true);
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (find(item) != null) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree size limit reached");
            }

            if (root.keyCount == maxKeys) {
                Node newRoot = new Node(maxKeys, maxChildren, false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }
            insertNonFull(root, item);
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            if (find(item) == null) {
                return false;
            }

            delete(root, item);
            if (!root.leaf && root.keyCount == 0) {
                root = root.children[0];
            }
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (monitor) {
            return find(item) != null;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (size == 0) {
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
            if (size == 0) {
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
            snapshot(root, result, new int[1]);
            return result;
        }
    }

    private Node find(T item) {
        Node node = root;
        while (node != null) {
            int index = findIndex(node, item);
            if (index < node.keyCount && order.compare(keyAt(node, index), item) == 0) {
                return node;
            }
            if (node.leaf) {
                return null;
            }
            node = node.children[index];
        }
        return null;
    }

    private int findIndex(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && order.compare(keyAt(node, index), item) < 0) {
            index++;
        }
        return index;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.keyCount - 1;
        if (node.leaf) {
            while (index >= 0 && order.compare(keyAt(node, index), item) > 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.keyCount++;
            return;
        }

        while (index >= 0 && order.compare(keyAt(node, index), item) > 0) {
            index--;
        }
        index++;
        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            if (order.compare(keyAt(node, index), item) < 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node full = parent.children[index];
        Node right = new Node(maxKeys, maxChildren, full.leaf);
        int degree = minimumDegree;

        right.keyCount = degree - 1;
        for (int i = 0; i < degree - 1; i++) {
            right.keys[i] = full.keys[i + degree];
            full.keys[i + degree] = null;
        }
        Object middleKey = full.keys[degree - 1];
        full.keys[degree - 1] = null;

        if (!full.leaf) {
            for (int i = 0; i < degree; i++) {
                right.children[i] = full.children[i + degree];
                full.children[i + degree] = null;
            }
        }
        full.keyCount = degree - 1;

        for (int i = parent.keyCount; i >= index + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;
        for (int i = parent.keyCount - 1; i >= index; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[index] = middleKey;
        parent.keyCount++;
    }

    private void delete(Node node, T item) {
        int index = findIndex(node, item);
        if (index < node.keyCount && order.compare(keyAt(node, index), item) == 0) {
            if (node.leaf) {
                removeFromLeaf(node, index);
            } else {
                deleteFromInternal(node, index);
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        if (node.children[index].keyCount == minimumDegree - 1) {
            if (index > 0 && node.children[index - 1].keyCount >= minimumDegree) {
                borrowFromPrevious(node, index);
            } else if (index < node.keyCount
                    && node.children[index + 1].keyCount >= minimumDegree) {
                borrowFromNext(node, index);
            } else if (index < node.keyCount) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }
        delete(node.children[index], item);
    }

    private void removeFromLeaf(Node node, int index) {
        for (int i = index; i < node.keyCount - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
    }

    private void deleteFromInternal(Node node, int index) {
        T key = keyAt(node, index);
        Node left = node.children[index];
        Node right = node.children[index + 1];

        if (left.keyCount >= minimumDegree) {
            T predecessor = maximumKey(left);
            node.keys[index] = predecessor;
            delete(left, predecessor);
        } else if (right.keyCount >= minimumDegree) {
            T successor = minimumKey(right);
            node.keys[index] = successor;
            delete(right, successor);
        } else {
            Node merged = mergeChildren(node, index);
            delete(merged, key);
        }
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
            for (int i = 0; i < sibling.keyCount; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.keyCount] = null;
        }

        parent.keys[index] = sibling.keys[0];
        for (int i = 0; i < sibling.keyCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;
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
        left.keyCount = leftCount + 1 + rightCount;

        for (int i = index; i < parent.keyCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[parent.keyCount - 1] = null;
        for (int i = index + 1; i < parent.keyCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[parent.keyCount] = null;
        parent.keyCount--;
        return left;
    }

    private void snapshot(Node node, Object[] result, int[] position) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                snapshot(node.children[i], result, position);
            }
            result[position[0]++] = node.keys[i];
        }
        if (!node.leaf) {
            snapshot(node.children[node.keyCount], result, position);
        }
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }
}
