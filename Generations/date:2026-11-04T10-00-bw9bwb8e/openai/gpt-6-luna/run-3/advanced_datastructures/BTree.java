@BTree.ThreadSafe
public final class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<T> {
        int compare(T left, T right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        boolean leaf;
        int count;

        Node(boolean leaf, int maxKeys, int maxChildren) {
            this.leaf = leaf;
            this.keys = new Object[maxKeys];
            this.children = new Node[maxChildren];
        }
    }

    private final Object monitor = new Object();
    private final Order<T> order;
    private final int minimumDegree;
    private final int maxKeys;
    private final int maxChildren;

    private Node root;
    private int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2) {
            throw new IllegalArgumentException("minimumDegree must be at least 2");
        }
        if (order == null) {
            throw new NullPointerException("order");
        }

        long keyCapacity = 2L * minimumDegree - 1L;
        long childCapacity = 2L * minimumDegree;
        if (keyCapacity > Integer.MAX_VALUE || childCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("minimumDegree causes node capacity overflow");
        }

        this.minimumDegree = minimumDegree;
        this.maxKeys = (int) keyCapacity;
        this.maxChildren = (int) childCapacity;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (find(item) != null) {
                return false;
            }

            if (root == null) {
                root = new Node(true, maxKeys, maxChildren);
                root.keys[0] = item;
                root.count = 1;
                size++;
                return true;
            }

            if (root.count == maxKeys) {
                Node newRoot = new Node(false, maxKeys, maxChildren);
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
            if (root == null || find(item) == null) {
                return false;
            }

            delete(root, item);
            size--;

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
            return find(item) != null;
        }
    }

    public T first() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }

            Node node = root;
            while (!node.leaf) {
                node = node.children[0];
            }
            return cast(node.keys[0]);
        }
    }

    public T last() {
        synchronized (monitor) {
            if (root == null) {
                throw new IllegalStateException("tree is empty");
            }

            Node node = root;
            while (!node.leaf) {
                node = node.children[node.count];
            }
            return cast(node.keys[node.count - 1]);
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
                copyInOrder(root, result, 0);
            }
            return result;
        }
    }

    private Node find(T item) {
        Node node = root;
        while (node != null) {
            int index = 0;
            while (index < node.count && compare(item, node.keys[index]) > 0) {
                index++;
            }
            if (index < node.count && compare(item, node.keys[index]) == 0) {
                return node;
            }
            if (node.leaf) {
                return null;
            }
            node = node.children[index];
        }
        return null;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.count - 1;

        if (node.leaf) {
            while (index >= 0 && compare(item, node.keys[index]) < 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.count++;
            return;
        }

        while (index >= 0 && compare(item, node.keys[index]) < 0) {
            index--;
        }
        index++;

        if (node.children[index].count == maxKeys) {
            splitChild(node, index);
            int comparison = compare(item, node.keys[index]);
            if (comparison > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int childIndex) {
        Node full = parent.children[childIndex];
        Node right = new Node(full.leaf, maxKeys, maxChildren);
        int degree = minimumDegree;

        right.count = degree - 1;
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
        full.count = degree - 1;

        for (int j = parent.count; j >= childIndex + 1; j--) {
            parent.children[j + 1] = parent.children[j];
        }
        parent.children[childIndex + 1] = right;

        for (int j = parent.count - 1; j >= childIndex; j--) {
            parent.keys[j + 1] = parent.keys[j];
        }
        parent.keys[childIndex] = median;
        parent.count++;
    }

    private void delete(Node node, T item) {
        int index = 0;
        while (index < node.count && compare(item, node.keys[index]) > 0) {
            index++;
        }

        if (index < node.count && compare(item, node.keys[index]) == 0) {
            if (node.leaf) {
                removeKeyFromLeaf(node, index);
            } else {
                deleteFromInternalNode(node, index);
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        boolean wasLastChild = index == node.count;
        if (node.children[index].count == minimumDegree - 1) {
            fillChild(node, index);
        }

        if (wasLastChild && index > node.count) {
            delete(node.children[index - 1], item);
        } else {
            delete(node.children[index], item);
        }
    }

    private void removeKeyFromLeaf(Node node, int index) {
        for (int j = index + 1; j < node.count; j++) {
            node.keys[j - 1] = node.keys[j];
        }
        node.keys[node.count - 1] = null;
        node.count--;
    }

    private void deleteFromInternalNode(Node node, int index) {
        Object key = node.keys[index];
        Node left = node.children[index];
        Node right = node.children[index + 1];

        if (left.count >= minimumDegree) {
            Object predecessor = predecessor(left);
            node.keys[index] = predecessor;
            delete(left, cast(predecessor));
        } else if (right.count >= minimumDegree) {
            Object successor = successor(right);
            node.keys[index] = successor;
            delete(right, cast(successor));
        } else {
            mergeChildren(node, index);
            delete(node.children[index], cast(key));
        }
    }

    private Object predecessor(Node node) {
        while (!node.leaf) {
            node = node.children[node.count];
        }
        return node.keys[node.count - 1];
    }

    private Object successor(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return node.keys[0];
    }

    private void fillChild(Node parent, int index) {
        if (index > 0 && parent.children[index - 1].count >= minimumDegree) {
            borrowFromPrevious(parent, index);
        } else if (index < parent.count
                && parent.children[index + 1].count >= minimumDegree) {
            borrowFromNext(parent, index);
        } else if (index < parent.count) {
            mergeChildren(parent, index);
        } else {
            mergeChildren(parent, index - 1);
        }
    }

    private void borrowFromPrevious(Node parent, int index) {
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

        child.count++;
        sibling.count--;
    }

    private void borrowFromNext(Node parent, int index) {
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

        child.count++;
        sibling.count--;
    }

    private void mergeChildren(Node parent, int index) {
        Node left = parent.children[index];
        Node right = parent.children[index + 1];
        int leftCount = left.count;

        left.keys[leftCount] = parent.keys[index];
        for (int j = 0; j < right.count; j++) {
            left.keys[leftCount + 1 + j] = right.keys[j];
            right.keys[j] = null;
        }

        if (!left.leaf) {
            for (int j = 0; j <= right.count; j++) {
                left.children[leftCount + 1 + j] = right.children[j];
                right.children[j] = null;
            }
        }
        left.count += right.count + 1;

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

    private int copyInOrder(Node node, Object[] destination, int offset) {
        for (int i = 0; i < node.count; i++) {
            if (!node.leaf) {
                offset = copyInOrder(node.children[i], destination, offset);
            }
            destination[offset++] = node.keys[i];
        }
        if (!node.leaf) {
            offset = copyInOrder(node.children[node.count], destination, offset);
        }
        return offset;
    }

    private int compare(T left, Object right) {
        return order.compare(left, cast(right));
    }

    @SuppressWarnings("unchecked")
    private T cast(Object value) {
        return (T) value;
    }
}
