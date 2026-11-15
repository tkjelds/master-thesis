@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        final boolean leaf;
        int keyCount;

        Node(int maximumKeys, int maximumChildren, boolean leaf) {
            this.keys = new Object[maximumKeys];
            this.children = new Node[maximumChildren];
            this.leaf = leaf;
        }
    }

    private final int minimumDegree;
    private final int maximumKeys;
    private final int maximumChildren;
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
        this.maximumChildren = 2 * minimumDegree;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            if (root == null) {
                root = newNode(true);
                root.keys[0] = item;
                root.keyCount = 1;
                size++;
                return true;
            }

            if (root.keyCount == maximumKeys) {
                Node newRoot = newNode(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }

            if (!insertNonFull(root, item)) {
                return false;
            }
            size++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            if (root == null) {
                return false;
            }

            boolean removed = deleteFromNode(root, item);
            if (root.keyCount == 0) {
                root = root.leaf ? null : root.children[0];
            }
            if (removed) {
                size--;
            }
            return removed;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            Node node = root;
            while (node != null) {
                int index = 0;
                while (index < node.keyCount && compare(item, node.keys[index]) > 0) {
                    index++;
                }
                if (index < node.keyCount && compare(item, node.keys[index]) == 0) {
                    return true;
                }
                node = node.leaf ? null : node.children[index];
            }
            return false;
        }
    }

    public T first() {
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            return size;
        }
    }

    public Object[] snapshot() {
        synchronized (this) {
            Object[] result = new Object[size];
            copyInOrder(root, result, new int[] {0});
            return result;
        }
    }

    private Node newNode(boolean leaf) {
        return new Node(maximumKeys, maximumChildren, leaf);
    }

    private int compare(T left, Object right) {
        return order.compare(left, cast(right));
    }

    @SuppressWarnings("unchecked")
    private T cast(Object value) {
        return (T) value;
    }

    private T keyAt(Node node, int index) {
        return cast(node.keys[index]);
    }

    private boolean insertNonFull(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && compare(item, node.keys[index]) > 0) {
            index++;
        }
        if (index < node.keyCount && compare(item, node.keys[index]) == 0) {
            return false;
        }

        if (node.leaf) {
            for (int i = node.keyCount; i > index; i--) {
                node.keys[i] = node.keys[i - 1];
            }
            node.keys[index] = item;
            node.keyCount++;
            return true;
        }

        if (node.children[index].keyCount == maximumKeys) {
            splitChild(node, index);
            int result = compare(item, node.keys[index]);
            if (result == 0) {
                return false;
            }
            if (result > 0) {
                index++;
            }
        }
        return insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node full = parent.children[index];
        Node right = newNode(full.leaf);
        int degree = minimumDegree;

        right.keyCount = degree - 1;
        for (int i = 0; i < degree - 1; i++) {
            right.keys[i] = full.keys[i + degree];
            full.keys[i + degree] = null;
        }
        Object median = full.keys[degree - 1];
        full.keys[degree - 1] = null;

        if (!full.leaf) {
            for (int i = 0; i < degree; i++) {
                right.children[i] = full.children[i + degree];
                full.children[i + degree] = null;
            }
        }
        full.keyCount = degree - 1;

        for (int i = parent.keyCount; i > index; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;

        for (int i = parent.keyCount; i > index; i--) {
            parent.keys[i] = parent.keys[i - 1];
        }
        parent.keys[index] = median;
        parent.keyCount++;
    }

    private boolean deleteFromNode(Node node, T item) {
        int index = 0;
        while (index < node.keyCount && compare(item, node.keys[index]) > 0) {
            index++;
        }

        if (index < node.keyCount && compare(item, node.keys[index]) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
                return true;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.keyCount >= minimumDegree) {
                T predecessor = maximumKey(left);
                node.keys[index] = predecessor;
                return deleteFromNode(left, predecessor);
            }
            if (right.keyCount >= minimumDegree) {
                T successor = minimumKey(right);
                node.keys[index] = successor;
                return deleteFromNode(right, successor);
            }

            Node merged = mergeChildren(node, index);
            return deleteFromNode(merged, item);
        }

        if (node.leaf) {
            return false;
        }

        Node child = node.children[index];
        if (child.keyCount == minimumDegree - 1) {
            if (index > 0 && node.children[index - 1].keyCount >= minimumDegree) {
                borrowFromPrevious(node, index);
            } else if (index < node.keyCount
                    && node.children[index + 1].keyCount >= minimumDegree) {
                borrowFromNext(node, index);
            } else if (index < node.keyCount) {
                child = mergeChildren(node, index);
            } else {
                child = mergeChildren(node, index - 1);
            }
        }
        return deleteFromNode(child, item);
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

    private void borrowFromPrevious(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int i = child.keyCount; i > 0; i--) {
            child.keys[i] = child.keys[i - 1];
        }
        if (!child.leaf) {
            for (int i = child.keyCount + 1; i > 0; i--) {
                child.children[i] = child.children[i - 1];
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

    private void copyInOrder(Node node, Object[] result, int[] position) {
        if (node == null) {
            return;
        }
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
}
