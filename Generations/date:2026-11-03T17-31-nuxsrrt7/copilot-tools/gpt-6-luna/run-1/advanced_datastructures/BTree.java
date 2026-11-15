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

        Node(int maxKeys, int childCapacity, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[childCapacity];
            this.leaf = leaf;
        }
    }

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maxKeys;
    private final int childCapacity;
    private final Order<T> order;
    private Node root;
    private int elementCount;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        this.minimumDegree = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.childCapacity = 2 * minimumDegree;
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            if (find(item) != null) {
                return false;
            }
            if (elementCount == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree size exceeds int capacity");
            }

            if (root == null) {
                root = newNode(true);
                root.keys[0] = item;
                root.keyCount = 1;
                elementCount++;
                return true;
            }

            if (root.keyCount == maxKeys) {
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
        requireNonNull(item);
        synchronized (monitor) {
            if (find(item) == null) {
                return false;
            }

            delete(root, item);
            elementCount--;
            if (root.keyCount == 0) {
                root = root.leaf ? null : root.children[0];
            }
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (monitor) {
            return find(item) != null;
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
            return elementCount;
        }
    }

    public Object[] snapshot() {
        synchronized (monitor) {
            Object[] result = new Object[elementCount];
            if (root != null) {
                appendSnapshot(root, result, new int[1]);
            }
            return result;
        }
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private Node newNode(boolean leaf) {
        return new Node(maxKeys, childCapacity, leaf);
    }

    private int compare(T left, Object right) {
        @SuppressWarnings("unchecked")
        T rightItem = (T) right;
        return order.compare(left, rightItem);
    }

    private int findPosition(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && compare(item, node.keys[index]) > 0) {
            index++;
        }
        return index;
    }

    private Node find(T item) {
        Node node = root;
        while (node != null) {
            int index = findPosition(node, item);
            if (index < node.keyCount && compare(item, node.keys[index]) == 0) {
                return node;
            }
            if (node.leaf) {
                return null;
            }
            node = node.children[index];
        }
        return null;
    }

    private void splitChild(Node parent, int childIndex) {
        Node full = parent.children[childIndex];
        Node right = newNode(full.leaf);
        int medianIndex = minimumDegree - 1;

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

        Object median = full.keys[medianIndex];
        full.keys[medianIndex] = null;
        full.keyCount = medianIndex;

        for (int i = parent.keyCount; i >= childIndex + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = right;

        for (int i = parent.keyCount - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[childIndex] = median;
        parent.keyCount++;
    }

    private void insertNonFull(Node node, T item) {
        int index = findPosition(node, item);
        if (node.leaf) {
            for (int i = node.keyCount - 1; i >= index; i--) {
                node.keys[i + 1] = node.keys[i];
            }
            node.keys[index] = item;
            node.keyCount++;
            return;
        }

        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            if (compare(item, node.keys[index]) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void delete(Node node, T item) {
        int index = findPosition(node, item);

        if (index < node.keyCount && compare(item, node.keys[index]) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
            } else {
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
                    mergeChildren(node, index);
                    delete(node.children[index], item);
                }
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        Node child = node.children[index];
        if (child.keyCount == minimumDegree - 1) {
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

    private void removeKeyAt(Node node, int index) {
        for (int i = index; i < node.keyCount - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
    }

    private void borrowFromPrevious(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex - 1];

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

        child.keys[0] = parent.keys[childIndex - 1];
        parent.keys[childIndex - 1] = sibling.keys[sibling.keyCount - 1];
        sibling.keys[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void borrowFromNext(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex + 1];

        child.keys[child.keyCount] = parent.keys[childIndex];
        if (!child.leaf) {
            child.children[child.keyCount + 1] = sibling.children[0];
            for (int i = 0; i < sibling.keyCount; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.keyCount] = null;
        }

        parent.keys[childIndex] = sibling.keys[0];
        for (int i = 0; i < sibling.keyCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void mergeChildren(Node parent, int separatorIndex) {
        Node left = parent.children[separatorIndex];
        Node right = parent.children[separatorIndex + 1];
        int leftCount = left.keyCount;

        left.keys[leftCount] = parent.keys[separatorIndex];
        for (int i = 0; i < right.keyCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            right.keys[i] = null;
        }
        if (!left.leaf) {
            for (int i = 0; i <= right.keyCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }
        left.keyCount = leftCount + 1 + right.keyCount;

        for (int i = separatorIndex; i < parent.keyCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[parent.keyCount - 1] = null;
        for (int i = separatorIndex + 1; i < parent.keyCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[parent.keyCount] = null;
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

    private T keyAt(Node node, int index) {
        @SuppressWarnings("unchecked")
        T result = (T) node.keys[index];
        return result;
    }

    private void appendSnapshot(Node node, Object[] result, int[] position) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                appendSnapshot(node.children[i], result, position);
            }
            result[position[0]++] = node.keys[i];
        }
        if (!node.leaf) {
            appendSnapshot(node.children[node.keyCount], result, position);
        }
    }
}
