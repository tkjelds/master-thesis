interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        int count;

        Node(int maxKeys, int maxChildren, boolean leaf) {
            keys = new Object[maxKeys];
            children = leaf ? null : new Node[maxChildren];
        }

        boolean isLeaf() {
            return children == null;
        }
    }

    private final Object lock = new Object();
    private final Order<T> order;
    private final int degree;
    private final int maxKeys;
    private final int maxChildren;
    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || 2L * minimumDegree > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }
        this.order = order;
        degree = minimumDegree;
        maxChildren = 2 * minimumDegree;
        maxKeys = maxChildren - 1;
    }

    @SuppressWarnings("unchecked")
    private T key(Node node, int index) {
        return (T) node.keys[index];
    }

    private Node newNode(boolean leaf) {
        return new Node(maxKeys, maxChildren, leaf);
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
            node = node.isLeaf() ? null : node.children[i];
        }
        return false;
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            return containsKey(item);
        }
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
            } else if (root.count == maxKeys) {
                Node newRoot = newNode(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }
            insertNonFull(root, item);
            size++;
            return true;
        }
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = newNode(left.isLeaf());
        right.count = degree - 1;
        for (int j = 0; j < degree - 1; j++) {
            right.keys[j] = left.keys[j + degree];
            left.keys[j + degree] = null;
        }
        if (!left.isLeaf()) {
            for (int j = 0; j < degree; j++) {
                right.children[j] = left.children[j + degree];
                left.children[j + degree] = null;
            }
        }
        Object median = left.keys[degree - 1];
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
        if (node.isLeaf()) {
            while (i >= 0 && order.compare(item, key(node, i)) < 0) {
                node.keys[i + 1] = node.keys[i];
                i--;
            }
            node.keys[i + 1] = item;
            node.count++;
            return;
        }
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

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
        synchronized (lock) {
            if (!containsKey(item)) {
                return false;
            }
            delete(root, item);
            if (root.count == 0) {
                root = root.isLeaf() ? null : root.children[0];
            }
            size--;
            return true;
        }
    }

    private void delete(Node node, T item) {
        int i = 0;
        while (i < node.count && order.compare(item, key(node, i)) > 0) {
            i++;
        }
        if (i < node.count && order.compare(item, key(node, i)) == 0) {
            if (node.isLeaf()) {
                for (int j = i + 1; j < node.count; j++) {
                    node.keys[j - 1] = node.keys[j];
                }
                node.keys[--node.count] = null;
            } else if (node.children[i].count >= degree) {
                T predecessor = extreme(node.children[i], false);
                node.keys[i] = predecessor;
                delete(node.children[i], predecessor);
            } else if (node.children[i + 1].count >= degree) {
                T successor = extreme(node.children[i + 1], true);
                node.keys[i] = successor;
                delete(node.children[i + 1], successor);
            } else {
                mergeChildren(node, i);
                delete(node.children[i], item);
            }
            return;
        }

        Node child = node.children[i];
        if (child.count == degree - 1) {
            if (i > 0 && node.children[i - 1].count >= degree) {
                borrowPrevious(node, i);
            } else if (i < node.count && node.children[i + 1].count >= degree) {
                borrowNext(node, i);
            } else if (i < node.count) {
                mergeChildren(node, i);
            } else {
                mergeChildren(node, i - 1);
                i--;
            }
        }
        delete(node.children[i], item);
    }

    private void borrowPrevious(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];
        for (int j = child.count - 1; j >= 0; j--) {
            child.keys[j + 1] = child.keys[j];
        }
        if (!child.isLeaf()) {
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

    private void borrowNext(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index + 1];
        child.keys[child.count] = parent.keys[index];
        if (!child.isLeaf()) {
            child.children[child.count + 1] = sibling.children[0];
            for (int j = 1; j <= sibling.count; j++) {
                sibling.children[j - 1] = sibling.children[j];
            }
            sibling.children[sibling.count] = null;
        }
        parent.keys[index] = sibling.keys[0];
        for (int j = 1; j < sibling.count; j++) {
            sibling.keys[j - 1] = sibling.keys[j];
        }
        sibling.keys[sibling.count - 1] = null;
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
        if (!left.isLeaf()) {
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

    private T extreme(Node node, boolean first) {
        while (!node.isLeaf()) {
            node = node.children[first ? 0 : node.count];
        }
        return key(node, first ? 0 : node.count - 1);
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return extreme(root, true);
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }
            return extreme(root, false);
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
