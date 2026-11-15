interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        boolean leaf;
        int count;

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
    private int elementCount;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        this.minimumDegree = minimumDegree;
        this.maxChildren = 2 * minimumDegree;
        this.maxKeys = maxChildren - 1;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (root == null) {
                root = new Node(maxKeys, maxChildren, true);
                root.keys[0] = item;
                root.count = 1;
                elementCount++;
                return true;
            }

            if (root.count == maxKeys) {
                Node newRoot = new Node(maxKeys, maxChildren, false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }

            if (!insertNonFull(root, item)) {
                return false;
            }

            elementCount++;
            return true;
        }
    }

    public boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (root == null || !delete(root, item)) {
                return false;
            }

            elementCount--;
            if (root.count == 0) {
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
            Node node = root;
            while (node != null) {
                int index = 0;
                while (index < node.count
                        && compare(item, node.keys[index]) > 0) {
                    index++;
                }

                if (index < node.count
                        && compare(item, node.keys[index]) == 0) {
                    return true;
                }

                if (node.leaf) {
                    return false;
                }
                node = node.children[index];
            }
            return false;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("B-tree is empty");
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
                throw new IllegalStateException("B-tree is empty");
            }

            Node node = root;
            while (!node.leaf) {
                node = node.children[node.count];
            }
            return keyAt(node, node.count - 1);
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
            fillSnapshot(root, result, 0);
            return result;
        }
    }

    private boolean insertNonFull(Node node, T item) {
        int index = node.count - 1;

        if (node.leaf) {
            while (index >= 0) {
                int comparison = compare(item, node.keys[index]);
                if (comparison == 0) {
                    return false;
                }
                if (comparison > 0) {
                    break;
                }
                node.keys[index + 1] = node.keys[index];
                index--;
            }

            node.keys[index + 1] = item;
            node.count++;
            return true;
        }

        while (index >= 0) {
            int comparison = compare(item, node.keys[index]);
            if (comparison == 0) {
                return false;
            }
            if (comparison > 0) {
                break;
            }
            index--;
        }

        index++;
        if (node.children[index].count == maxKeys) {
            splitChild(node, index);
            int comparison = compare(item, node.keys[index]);
            if (comparison == 0) {
                return false;
            }
            if (comparison > 0) {
                index++;
            }
        }

        return insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int childIndex) {
        Node full = parent.children[childIndex];
        Node right = new Node(maxKeys, maxChildren, full.leaf);
        int degree = minimumDegree;

        right.count = degree - 1;
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
        full.count = degree - 1;

        for (int i = parent.count; i >= childIndex + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = right;

        for (int i = parent.count - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[childIndex] = median;
        parent.count++;
    }

    private boolean delete(Node node, T item) {
        int index = 0;
        while (index < node.count && compare(item, node.keys[index]) > 0) {
            index++;
        }

        if (index < node.count && compare(item, node.keys[index]) == 0) {
            if (node.leaf) {
                removeKeyFromLeaf(node, index);
                return true;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];

            if (left.count >= minimumDegree) {
                Object predecessor = maximumKey(left);
                node.keys[index] = predecessor;
                return delete(left, keyAsT(predecessor));
            }

            if (right.count >= minimumDegree) {
                Object successor = minimumKey(right);
                node.keys[index] = successor;
                return delete(right, keyAsT(successor));
            }

            Node merged = mergeChildren(node, index);
            return delete(merged, item);
        }

        if (node.leaf) {
            return false;
        }

        boolean wasLastChild = index == node.count;
        Node child = node.children[index];

        if (child.count == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].count >= minimumDegree) {
                borrowFromPrevious(node, index);
            } else if (index < node.count
                    && node.children[index + 1].count >= minimumDegree) {
                borrowFromNext(node, index);
            } else if (index < node.count) {
                child = mergeChildren(node, index);
            } else {
                child = mergeChildren(node, index - 1);
                index--;
            }
        }

        if (wasLastChild && index > node.count) {
            return delete(node.children[index - 1], item);
        }
        return delete(node.children[index], item);
    }

    private void removeKeyFromLeaf(Node node, int index) {
        for (int i = index; i < node.count - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.count - 1] = null;
        node.count--;
    }

    private void borrowFromPrevious(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex - 1];

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

        child.keys[0] = parent.keys[childIndex - 1];
        parent.keys[childIndex - 1] = sibling.keys[sibling.count - 1];
        sibling.keys[sibling.count - 1] = null;

        child.count++;
        sibling.count--;
    }

    private void borrowFromNext(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex + 1];

        child.keys[child.count] = parent.keys[childIndex];
        if (!child.leaf) {
            child.children[child.count + 1] = sibling.children[0];
        }
        parent.keys[childIndex] = sibling.keys[0];

        for (int i = 0; i < sibling.count - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.count - 1] = null;

        if (!sibling.leaf) {
            for (int i = 0; i < sibling.count; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.count] = null;
        }

        child.count++;
        sibling.count--;
    }

    private Node mergeChildren(Node parent, int separatorIndex) {
        Node left = parent.children[separatorIndex];
        Node right = parent.children[separatorIndex + 1];
        int leftCount = left.count;

        left.keys[leftCount] = parent.keys[separatorIndex];
        for (int i = 0; i < right.count; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            right.keys[i] = null;
        }

        if (!left.leaf) {
            for (int i = 0; i <= right.count; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }

        left.count = leftCount + right.count + 1;

        int oldParentCount = parent.count;
        for (int i = separatorIndex; i < oldParentCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[oldParentCount - 1] = null;

        for (int i = separatorIndex + 1; i < oldParentCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[oldParentCount] = null;
        parent.count--;

        return left;
    }

    private Object minimumKey(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return node.keys[0];
    }

    private Object maximumKey(Node node) {
        while (!node.leaf) {
            node = node.children[node.count];
        }
        return node.keys[node.count - 1];
    }

    private int fillSnapshot(Node node, Object[] destination, int position) {
        if (node == null) {
            return position;
        }

        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                position = fillSnapshot(node.children[i], destination, position);
            }
            destination[position++] = node.keys[i];
        }

        if (!node.leaf) {
            position = fillSnapshot(node.children[node.count], destination, position);
        }
        return position;
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }

    @SuppressWarnings("unchecked")
    private T keyAsT(Object key) {
        return (T) key;
    }

    @SuppressWarnings("unchecked")
    private int compare(T item, Object key) {
        return order.compare(item, (T) key);
    }
}
