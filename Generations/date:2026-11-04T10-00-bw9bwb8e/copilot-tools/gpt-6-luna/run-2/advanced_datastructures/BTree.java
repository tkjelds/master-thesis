@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        final boolean leaf;
        int keyCount;

        Node(int maxKeys, int maxChildren, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[maxChildren];
            this.leaf = leaf;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private final int minimumDegree;
    private final int maxKeys;
    private final int maxChildren;
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
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (findIndex(root, item) >= 0) {
                return false;
            }

            if (root == null) {
                root = new Node(maxKeys, maxChildren, true);
                root.keys[0] = item;
                root.keyCount = 1;
                size++;
                return true;
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
        synchronized (lock) {
            if (findIndex(root, item) < 0) {
                return false;
            }

            delete(root, item);
            size--;
            if (root.keyCount == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return findIndex(root, item) >= 0;
        }
    }

    public T first() {
        synchronized (lock) {
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
        synchronized (lock) {
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
        synchronized (lock) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[size];
            if (root != null) {
                writeSnapshot(root, result, 0);
                int written = writeSnapshot(root, result, 0);
                if (written != size) {
                    throw new IllegalStateException("Tree size invariant violated");
                }
            }
            return result;
        }
    }

    private int findIndex(Node node, T item) {
        while (node != null) {
            int index = 0;
            while (index < node.keyCount && compare(keyAt(node, index), item) < 0) {
                index++;
            }
            if (index < node.keyCount && compare(keyAt(node, index), item) == 0) {
                return index;
            }
            if (node.leaf) {
                return -1;
            }
            node = node.children[index];
        }
        return -1;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.keyCount - 1;
        if (node.leaf) {
            while (index >= 0 && compare(item, keyAt(node, index)) < 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.keyCount++;
            return;
        }

        while (index >= 0 && compare(item, keyAt(node, index)) < 0) {
            index--;
        }
        index++;
        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            if (compare(item, keyAt(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node full = parent.children[index];
        Node right = new Node(maxKeys, maxChildren, full.leaf);
        right.keyCount = minimumDegree - 1;

        for (int j = 0; j < minimumDegree - 1; j++) {
            right.keys[j] = full.keys[j + minimumDegree];
            full.keys[j + minimumDegree] = null;
        }
        if (!full.leaf) {
            for (int j = 0; j < minimumDegree; j++) {
                right.children[j] = full.children[j + minimumDegree];
                full.children[j + minimumDegree] = null;
            }
        }

        Object median = full.keys[minimumDegree - 1];
        full.keys[minimumDegree - 1] = null;
        full.keyCount = minimumDegree - 1;

        for (int j = parent.keyCount; j >= index + 1; j--) {
            parent.children[j + 1] = parent.children[j];
        }
        parent.children[index + 1] = right;
        for (int j = parent.keyCount - 1; j >= index; j--) {
            parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[index] = median;
        parent.keyCount++;
    }

    private void delete(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && compare(keyAt(node, index), item) < 0) {
            index++;
        }

        if (index < node.keyCount && compare(keyAt(node, index), item) == 0) {
            if (node.leaf) {
                removeKey(node, index);
            } else {
                Node left = node.children[index];
                Node right = node.children[index + 1];
                if (left.keyCount >= minimumDegree) {
                    Object predecessor = maximumKey(left);
                    node.keys[index] = predecessor;
                    delete(left, keyAsT(predecessor));
                } else if (right.keyCount >= minimumDegree) {
                    Object successor = minimumKey(right);
                    node.keys[index] = successor;
                    delete(right, keyAsT(successor));
                } else {
                    mergeChildren(node, index);
                    delete(left, item);
                }
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        boolean wasLastChild = index == node.keyCount;
        if (node.children[index].keyCount == minimumDegree - 1) {
            fillChild(node, index);
        }
        if (wasLastChild && index > node.keyCount) {
            delete(node.children[index - 1], item);
        } else {
            delete(node.children[index], item);
        }
    }

    private void fillChild(Node parent, int index) {
        if (index > 0 && parent.children[index - 1].keyCount >= minimumDegree) {
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

        for (int j = child.keyCount - 1; j >= 0; j--) {
            child.keys[j + 1] = child.keys[j];
        }
        if (!child.leaf) {
            for (int j = child.keyCount; j >= 0; j--) {
                child.children[j + 1] = child.children[j];
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

        for (int j = 1; j < sibling.keyCount; j++) {
            sibling.keys[j - 1] = sibling.keys[j];
        }
        sibling.keys[sibling.keyCount - 1] = null;
        if (!sibling.leaf) {
            for (int j = 1; j <= sibling.keyCount; j++) {
                sibling.children[j - 1] = sibling.children[j];
            }
            sibling.children[sibling.keyCount] = null;
        }

        child.keyCount++;
        sibling.keyCount--;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int leftCount = left.keyCount;

        left.keys[leftCount] = parent.keys[index];
        for (int j = 0; j < right.keyCount; j++) {
            left.keys[leftCount + 1 + j] = right.keys[j];
            right.keys[j] = null;
        }
        if (!left.leaf) {
            for (int j = 0; j <= right.keyCount; j++) {
                left.children[leftCount + 1 + j] = right.children[j];
                right.children[j] = null;
            }
        }
        left.keyCount = leftCount + right.keyCount + 1;

        for (int j = index + 1; j < parent.keyCount; j++) {
            parent.keys[j - 1] = parent.keys[j];
        }
        parent.keys[parent.keyCount - 1] = null;
        for (int j = index + 2; j <= parent.keyCount; j++) {
            parent.children[j - 1] = parent.children[j];
        }
        parent.children[parent.keyCount] = null;
        parent.keyCount--;
    }

    private void removeKey(Node node, int index) {
        for (int j = index + 1; j < node.keyCount; j++) {
            node.keys[j - 1] = node.keys[j];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
    }

    private Object minimumKey(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return node.keys[0];
    }

    private Object maximumKey(Node node) {
        while (!node.leaf) {
            node = node.children[node.keyCount];
        }
        return node.keys[node.keyCount - 1];
    }

    private int writeSnapshot(Node node, Object[] result, int index) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                index = writeSnapshot(node.children[i], result, index);
            }
            result[index++] = node.keys[i];
        }
        if (!node.leaf) {
            index = writeSnapshot(node.children[node.keyCount], result, index);
        }
        return index;
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }

    @SuppressWarnings("unchecked")
    private T keyAsT(Object key) {
        return (T) key;
    }
}
