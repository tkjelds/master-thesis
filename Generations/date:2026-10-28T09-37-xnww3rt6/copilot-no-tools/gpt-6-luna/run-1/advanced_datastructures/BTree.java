interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

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
    private final int minimumDegree;
    private final int maxKeys;
    private final int maxChildren;
    private final Order<T> order;
    private Node root;
    private int elementCount;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        long childCapacity = 2L * minimumDegree;
        if (childCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("minimumDegree causes node-array capacity overflow");
        }

        this.minimumDegree = minimumDegree;
        this.maxKeys = (int) (childCapacity - 1);
        this.maxChildren = (int) childCapacity;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            if (containsInternal(item)) {
                return false;
            }

            if (root == null) {
                root = new Node(maxKeys, maxChildren, true);
                root.keys[0] = item;
                root.keyCount = 1;
                elementCount++;
                return true;
            }

            if (root.keyCount == maxKeys) {
                Node newRoot = new Node(maxKeys, maxChildren, false);
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
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (lock) {
            if (root == null || !containsInternal(item)) {
                return false;
            }

            deleteFromNode(root, item);
            elementCount--;

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
            return containsInternal(item);
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
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
                throw new IllegalStateException("tree is empty");
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
            return elementCount;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[elementCount];
            if (root != null) {
                writeInOrder(root, result, new int[1]);
            }
            return result;
        }
    }

    private boolean containsInternal(T item) {
        Node node = root;
        while (node != null) {
            int index = 0;
            while (index < node.keyCount && order.compare(item, keyAt(node, index)) > 0) {
                index++;
            }
            if (index < node.keyCount && order.compare(item, keyAt(node, index)) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[index];
        }
        return false;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.keyCount - 1;

        if (node.leaf) {
            while (index >= 0 && order.compare(item, keyAt(node, index)) < 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.keyCount++;
            return;
        }

        while (index >= 0 && order.compare(item, keyAt(node, index)) < 0) {
            index--;
        }
        index++;

        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            int comparison = order.compare(item, keyAt(node, index));
            if (comparison > 0) {
                index++;
            } else if (comparison == 0) {
                return;
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

        if (!full.leaf) {
            for (int i = 0; i < degree; i++) {
                right.children[i] = full.children[i + degree];
                full.children[i + degree] = null;
            }
        }

        Object median = full.keys[degree - 1];
        full.keys[degree - 1] = null;
        full.keyCount = degree - 1;

        for (int i = parent.keyCount; i >= index + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;

        for (int i = parent.keyCount - 1; i >= index; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[index] = median;
        parent.keyCount++;
    }

    private void deleteFromNode(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && order.compare(item, keyAt(node, index)) > 0) {
            index++;
        }

        if (index < node.keyCount && order.compare(item, keyAt(node, index)) == 0) {
            if (node.leaf) {
                removeKeyFromLeaf(node, index);
            } else if (node.children[index].keyCount >= minimumDegree) {
                T predecessor = maximumKey(node.children[index]);
                node.keys[index] = predecessor;
                deleteFromNode(node.children[index], predecessor);
            } else if (node.children[index + 1].keyCount >= minimumDegree) {
                T successor = minimumKey(node.children[index + 1]);
                node.keys[index] = successor;
                deleteFromNode(node.children[index + 1], successor);
            } else {
                mergeChildren(node, index);
                deleteFromNode(node.children[index], item);
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

        deleteFromNode(node.children[index], item);
    }

    private void removeKeyFromLeaf(Node node, int index) {
        for (int i = index; i < node.keyCount - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
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

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int oldParentCount = parent.keyCount;
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

        for (int i = = index; i < oldParentCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[oldParentCount - 1] = null;

        for (int i = index + 1; i < oldParentCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[oldParentCount] = null;
        parent.keyCount--;
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

    private int writeInOrder(Node node, Object[] result, int offset) {
        if (node.leaf) {
            for (int i = 0; i < node.keyCount; i++) {
                result[offset++] = node.keys[i];
            }
            return offset;
        }

        for (int i = 0; i < node.keyCount; i++) {
            offset = writeInOrder(node.children[i], result, offset);
            result[offset++] = node.keys[i];
        }
        return writeInOrder(node.children[node.keyCount], result, offset);
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }
}
