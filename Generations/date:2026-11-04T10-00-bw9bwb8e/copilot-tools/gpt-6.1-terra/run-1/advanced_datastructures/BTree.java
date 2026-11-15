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
        int count;

        Node(int maxKeys, int maxChildren, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[maxChildren];
            this.leaf = leaf;
        }
    }

    private final Object lock = new Object();
    private final int degree;
    private final int maxKeys;
    private final int maxChildren;
    private final Order<T> order;
    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || 2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.degree = minimumDegree;
        this.maxChildren = 2 * minimumDegree;
        this.maxKeys = maxChildren - 1;
        this.order = order;
    }

    @SuppressWarnings("unchecked")
    private T key(Node node, int index) {
        return (T) node.keys[index];
    }

    private Node newNode(boolean leaf) {
        return new Node(maxKeys, maxChildren, leaf);
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return containsKey(item);
        }
    }

    private boolean containsKey(T item) {
        Node node = root;
        while (node != null) {
            int i = 0;
            while (i < node.count && order.compare(item, key(node, i)) > 0) {
                i++;
            }
            if (i < node.count && order.compare(item, key(node, i)) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[i];
        }
        return false;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (containsKey(item)) {
                return false;
            }
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree size exceeds int capacity");
            }
            if (root == null) {
                root = newNode(true);
                root.keys[0] = item;
                root.count = 1;
            } else {
                if (root.count == maxKeys) {
                    Node newRoot = newNode(false);
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

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = newNode(left.leaf);
        Object median = left.keys[degree - 1];
        right.count = degree - 1;
        for (int j = 0; j < degree - 1; j++) {
            right.keys[j] = left.keys[j + degree];
            left.keys[j + degree] = null;
        }
        if (!left.leaf) {
            for (int j = 0; j < degree; j++) {
                right.children[j] = left.children[j + degree];
                left.children[j + degree] = null;
            }
        }
        left.keys[degree - 1] = null;
        left.count = degree - 1;

        for (int j = parent.count; j > index; j--) {
            parent.children[j + 1] = parent.children[j];
        }
        parent.children[index + 1] = right;
        for (int j = parent.count - 1; j >= index; j--) {
            parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[index] = median;
        parent.count++;
    }

    private void insertNonFull(Node node, T item) {
        int i = node.count - 1;
        if (node.leaf) {
            while (i >= 0 && order.compare(item, key(node, i)) < 0) {
                node.keys[i + 1] = node.keys[i];
                i--;
            }
            node.keys[i + 1] = item;
            node.count++;
        } else {
            while (i >= 0 && order.compare(item, key(node, i)) < 0) {
                i--;
            }
            i++;
            if (node.children[i].count == maxKeys) {
                splitChild(node, i);
                if (order.compare(item, key(node, i)) > 0) {
                    i++;
                }
            }
            insertNonFull(node.children[i], item);
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (root == null) {
                return false;
            }
            boolean removed = delete(root, item);
            if (root.count == 0) {
                root = root.leaf ? null : root.children[0];
            }
            if (removed) {
                size--;
            }
            return removed;
        }
    }

    private boolean delete(Node node, T item) {
        int i = 0;
        while (i < node.count && order.compare(item, key(node, i)) > 0) {
            i++;
        }
        if (i < node.count && order.compare(item, key(node, i)) == 0) {
            if (node.leaf) {
                for (int j = i + 1; j < node.count; j++) {
                    node.keys[j - 1] = node.keys[j];
                }
                node.keys[--node.count] = null;
                return true;
            }
            Node left = node.children[i];
            Node right = node.children[i + 1];
            if (left.count >= degree) {
                Node predecessor = left;
                while (!predecessor.leaf) {
                    predecessor = predecessor.children[predecessor.count];
                }
                T replacement = key(predecessor, predecessor.count - 1);
                node.keys[i] = replacement;
                return delete(left, replacement);
            }
            if (right.count >= degree) {
                Node successor = right;
                while (!successor.leaf) {
                    successor = successor.children[0];
                }
                T replacement = key(successor, 0);
                node.keys[i] = replacement;
                return delete(right, replacement);
            }
            mergeChildren(node, i);
            return delete(left, item);
        }
        if (node.leaf) {
            return false;
        }
        if (node.children[i].count == degree - 1) {
            if (i > 0 && node.children[i - 1].count >= degree) {
                borrowFromLeft(node, i);
            } else if (i < node.count && node.children[i + 1].count >= degree) {
                borrowFromRight(node, i);
            } else if (i < node.count) {
                mergeChildren(node, i);
            } else {
                mergeChildren(node, i - 1);
                i--;
            }
        }
        return delete(node.children[i], item);
    }

    private void borrowFromLeft(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];
        for (int j = child.count - 1; j >= 0; j--) {
            child.keys[j + 1] = child.keys[j];
        }
        if (!child.leaf) {
            for (int j = child.count; j >= 0; j--) {
                child.children[j + 1] = child.children[j];
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
        if (!child.leaf) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];
        for (int j = 1; j < sibling.count; j++) {
            sibling.keys[j - 1] = sibling.keys[j];
        }
        sibling.keys[sibling.count - 1] = null;
        if (!sibling.leaf) {
            for (int j = 1; j <= sibling.count; j++) {
                sibling.children[j - 1] = sibling.children[j];
            }
            sibling.children[sibling.count] = null;
        }
        sibling.count--;
        child.count++;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int leftCount = left.count;
        left.keys[leftCount] = parent.keys[index];
        for (int j = 0; j < right.count; j++) {
            left.keys[leftCount + 1 + j] = right.keys[j];
        }
        if (!left.leaf) {
            for (int j = 0; j <= right.count; j++) {
                left.children[leftCount + 1 + j] = right.children[j];
            }
        }
        left.count = leftCount + 1 + right.count;
        for (int j = index + 1; j < parent.count; j++) {
            parent.keys[j - 1] = parent.keys[j];
        }
        parent.keys[parent.count - 1] = null;
        for (int j = index + 2; j <= parent.count; j++) {
            parent.children[j - 1] = parent.children[j];
        }
        parent.children[parent.count] = null;
        parent.count--;
    }

    // Empty extrema fail immediately rather than waiting for an insertion.
    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[0];
            }
            return key(node, 0);
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            Node node = root;
            while (!node.leaf) {
                node = node.children[node.count];
            }
            return key(node, node.count - 1);
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
                copyInOrder(root, result, 0);
            }
            return result;
        }
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
