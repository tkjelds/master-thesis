@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        int keyCount;
        boolean leaf = true;

        Node(int maximumKeys) {
            keys = new Object[maximumKeys];
            children = new Node[maximumKeys + 1];
        }
    }

    private final Object monitor = new Object();
    private final int minimumDegree;
    private final int maximumKeys;
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
        this.maximumKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (containsInternal(item)) {
                return false;
            }

            if (root == null) {
                root = new Node(maximumKeys);
                root.keys[0] = item;
                root.keyCount = 1;
                size++;
                return true;
            }

            if (root.keyCount == maximumKeys) {
                Node newRoot = new Node(maximumKeys);
                newRoot.leaf = false;
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
            if (!containsInternal(item)) {
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
                copyInOrder(root, result, new int[] {0});
            }
            return result;
        }
    }

    private boolean containsInternal(T item) {
        Node node = root;
        while (node != null) {
            int index = findKey(node, item);
            if (index < node.keyCount && order.compare(item, keyAt(node, index)) == 0) {
                return true;
            }
            if (node.leaf) {
                return false;
            }
            node = node.children[index];
        }
        return false;
    }

    private int findKey(Node node, T item) {
        int index = 0;
        while (index < node.keyCount
                && order.compare(item, keyAt(node, index)) > 0) {
            index++;
        }
        return index;
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

        index = findKey(node, item);
        if (node.children[index].keyCount == maximumKeys) {
            splitChild(node, index);
            int comparison = order.compare(item, keyAt(node, index));
            if (comparison > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        int degree = minimumDegree;
        Node full = parent.children[index];
        Node right = new Node(maximumKeys);
        right.leaf = full.leaf;
        right.keyCount = degree - 1;

        for (int j = 0; j < degree - 1; j++) {
            right.keys[j] = full.keys[j + degree];
            full.keys[j + degree] = null;
        }

        if (!full.leaf) {
            for (int j = 0; j < degree; j++) {
                right.children[j] = full.children[j + degree];
                full.children[j + degree] = null;
            }
        }

        Object median = full.keys[degree - 1];
        full.keys[degree - 1] = null;
        full.keyCount = degree - 1;

        for (int j = parent.keyCount; j > index; j--) {
            parent.children[j + 1] = parent.children[j];
        }
        parent.children[index + 1] = right;

        for (int j = parent.keyCount - 1; j >= index; j--) {
            parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[index] = median;
        parent.keyCount++;
        parent.leaf = false;
    }

    private void delete(Node node, T item) {
        int index = findKey(node, item);

        if (index < node.keyCount && order.compare(item, keyAt(node, index)) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
                return;
            }

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
                delete(merged, item);
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

        for (int j = 0; j < sibling.keyCount - 1; j++) {
            sibling.keys[j] = sibling.keys[j + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;

        if (!sibling.leaf) {
            for (int j = 0; j < sibling.keyCount; j++) {
                sibling.children[j] = sibling.children[j + 1];
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
        for (int j = 0; j < rightCount; j++) {
            left.keys[leftCount + 1 + j] = right.keys[j];
            right.keys[j] = null;
        }

        if (!left.leaf) {
            for (int j = 0; j <= rightCount; j++) {
                left.children[leftCount + 1 + j] = right.children[j];
                right.children[j] = null;
            }
        }

        left.keyCount = leftCount + rightCount + 1;

        for (int j = index; j < parent.keyCount - 1; j++) {
            parent.keys[j] = parent.keys[j + 1];
        }
        parent.keys[parent.keyCount - 1] = null;

        for (int j = index + 1; j < parent.keyCount; j++) {
            parent.children[j] = parent.children[j + 1];
        }
        parent.children[parent.keyCount] = null;
        parent.keyCount--;

        return left;
    }

    private void removeKeyAt(Node node, int index) {
        for (int j = index; j < node.keyCount - 1; j++) {
            node.keys[j] = node.keys[j + 1];
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

    private void copyInOrder(Node node, Object[] result, int[] position) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                copyInOrder(node.children[i], result, position);
            }
            result[position[0]++] = node.keys[i];
        }
        if (!node.leaf) {
            copyInOrder(node.children[node.keyCount], result, position);
        }
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }
}
