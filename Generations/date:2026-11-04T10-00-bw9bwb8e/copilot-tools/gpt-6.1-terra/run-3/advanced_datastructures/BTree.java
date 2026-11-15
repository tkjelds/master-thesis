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

        Node(boolean leaf, int maxKeys) {
            this.leaf = leaf;
            this.keys = new Object[maxKeys];
            this.children = new Node[maxKeys + 1];
        }
    }

    private final Object lock = new Object();
    private final int minimumDegree;
    private final int maxKeys;
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

        this.minimumDegree = minimumDegree;
        this.maxKeys = 2 * minimumDegree - 1;
        this.order = order;
    }

    public boolean add(T item) {
        requireItem(item);
        synchronized (lock) {
            if (find(item)) {
                return false;
            }

            // size() is an int, so a further distinct element cannot be represented.
            if (size == Integer.MAX_VALUE) {
                throw new IllegalStateException("Tree size limit reached");
            }

            if (root == null) {
                root = new Node(true, maxKeys);
                root.keys[0] = item;
                root.count = 1;
            } else {
                if (root.count == maxKeys) {
                    Node newRoot = new Node(false, maxKeys);
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

    public boolean remove(T item) {
        requireItem(item);
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

    public boolean contains(T item) {
        requireItem(item);
        synchronized (lock) {
            return find(item);
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

    private void requireItem(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    @SuppressWarnings("unchecked")
    private T key(Node node, int index) {
        return (T) node.keys[index];
    }

    private boolean find(T item) {
        Node node = root;
        while (node != null) {
            int index = 0;
            while (index < node.count && order.compare(item, key(node, index)) > 0) {
                index++;
            }
            if (index < node.count && order.compare(item, key(node, index)) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[index];
        }
        return false;
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = new Node(left.leaf, maxKeys);
        int t = minimumDegree;
        Object separator = left.keys[t - 1];

        right.count = t - 1;
        for (int i = 0; i < t - 1; i++) {
            right.keys[i] = left.keys[i + t];
            left.keys[i + t] = null;
        }
        left.keys[t - 1] = null;

        if (!left.leaf) {
            for (int i = 0; i < t; i++) {
                right.children[i] = left.children[i + t];
                left.children[i + t] = null;
            }
        }
        left.count = t - 1;

        for (int i = parent.count; i > index; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[index + 1] = right;

        for (int i = parent.count - 1; i >= index; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[index] = separator;
        parent.count++;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.count - 1;

        if (node.leaf) {
            while (index >= 0 && order.compare(item, key(node, index)) < 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.count++;
            return;
        }

        while (index >= 0 && order.compare(item, key(node, index)) < 0) {
            index--;
        }
        index++;

        if (node.children[index].count == maxKeys) {
            splitChild(node, index);
            if (order.compare(item, key(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private boolean delete(Node node, T item) {
        int index = 0;
        while (index < node.count && order.compare(item, key(node, index)) > 0) {
            index++;
        }

        if (index < node.count && order.compare(item, key(node, index)) == 0) {
            if (node.leaf) {
                for (int i = index + 1; i < node.count; i++) {
                    node.keys[i - 1] = node.keys[i];
                }
                node.keys[node.count - 1] = null;
                node.count--;
                return true;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.count >= minimumDegree) {
                Node predecessor = left;
                while (!predecessor.leaf) {
                    predecessor = predecessor.children[predecessor.count];
                }
                T replacement = key(predecessor, predecessor.count - 1);
                node.keys[index] = replacement;
                return delete(left, replacement);
            }
            if (right.count >= minimumDegree) {
                Node successor = right;
                while (!successor.leaf) {
                    successor = successor.children[0];
                }
                T replacement = key(successor, 0);
                node.keys[index] = replacement;
                return delete(right, replacement);
            }

            mergeChildren(node, index);
            return delete(left, item);
        }

        if (node.leaf) {
            return false;
        }

        Node child = node.children[index];
        if (child.count == minimumDegree - 1) {
            if (index > 0 && node.children[index - 1].count >= minimumDegree) {
                borrowFromLeft(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowFromRight(node, index);
            } else if (index < node.count) {
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                child = node.children[index - 1];
            }
        }
        return delete(child, item);
    }

    private void borrowFromLeft(Node parent, int index) {
        Node child = parent.children[index];
        Node sibling = parent.children[index - 1];

        for (int i = child.count - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (!child.leaf) {
            for (int i = child.count; i >= 0; i--) {
                child.children[i + 1] = child.children[i];
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
        int leftCount = left.count;

        left.keys[leftCount] = parent.keys[index];
        for (int i = 0; i < right.count; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
        }
        if (!left.leaf) {
            for (int i = 0; i <= right.count; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
            }
        }
        left.count = leftCount + 1 + right.count;

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
