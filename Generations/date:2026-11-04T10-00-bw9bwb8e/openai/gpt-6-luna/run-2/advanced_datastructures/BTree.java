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

        Node(int keyCapacity) {
            keys = new Object[keyCapacity];
            children = new Node[keyCapacity + 1];
        }

        boolean isLeaf() {
            return children[0] == null;
        }
    }

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

        synchronized (this) {
            if (findNode(item) != null) {
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

        synchronized (this) {
            if (root == null || findNode(item) == null) {
                return false;
            }

            delete(root, item);
            if (root.keyCount == 0) {
                root = root.isLeaf() ? null : root.children[0];
            }
            size--;
            return true;
        }
    }

    public boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (this) {
            return findNode(item) != null;
        }
    }

    public T first() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException("The tree is empty");
            }

            Node node = root;
            while (!node.isLeaf()) {
                node = node.children[0];
            }
            return keyAt(node, 0);
        }
    }

    public T last() {
        synchronized (this) {
            if (root == null) {
                throw new IllegalStateException("The tree is empty");
            }

            Node node = root;
            while (!node.isLeaf()) {
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
            int[] position = new int[1];
            if (root != null) {
                copyInOrder(root, result, position);
            }
            return result;
        }
    }

    private Node findNode(T item) {
        Node node = root;
        while (node != null) {
            int index = 0;
            while (index < node.keyCount
                    && order.compare(item, keyAt(node, index)) > 0) {
                index++;
            }

            if (index < node.keyCount
                    && order.compare(item, keyAt(node, index)) == 0) {
                return node;
            }

            node = node.isLeaf() ? null : node.children[index];
        }
        return null;
    }

    private void insertNonFull(Node node, T item) {
        int index = node.keyCount - 1;

        if (node.isLeaf()) {
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

        if (node.children[index].keyCount == maximumKeys) {
            splitChild(node, index);
            if (order.compare(item, keyAt(node, index)) > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int childIndex) {
        Node full = parent.children[childIndex];
        Node right = new Node(maximumKeys);
        int degree = minimumDegree;

        right.keyCount = degree - 1;
        for (int i = 0; i < degree - 1; i++) {
            right.keys[i] = full.keys[i + degree];
        }

        if (!full.isLeaf()) {
            for (int i = 0; i < degree; i++) {
                right.children[i] = full.children[i + degree];
                full.children[i + degree] = null;
            }
        }

        Object median = full.keys[degree - 1];
        for (int i = parent.keyCount; i >= childIndex + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = right;

        for (int i = parent.keyCount - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }
        parent.keys[childIndex] = median;
        parent.keyCount++;

        for (int i = degree - 1; i < maximumKeys; i++) {
            full.keys[i] = null;
        }
        full.keyCount = degree - 1;
    }

    private void delete(Node node, T item) {
        int index = 0;
        while (index < node.keyCount
                && order.compare(item, keyAt(node, index)) > 0) {
            index++;
        }

        if (index < node.keyCount
                && order.compare(item, keyAt(node, index)) == 0) {
            if (node.isLeaf()) {
                removeKeyFromLeaf(node, index);
            } else {
                deleteFromInternalNode(node, index);
            }
            return;
        }

        if (node.isLeaf()) {
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

    private void deleteFromInternalNode(Node node, int index) {
        Object target = node.keys[index];
        Node left = node.children[index];
        Node right = node.children[index + 1];

        if (left.keyCount >= minimumDegree) {
            Object predecessor = maximumKey(left);
            node.keys[index] = predecessor;
            delete(left, cast(predecessor));
        } else if (right.keyCount >= minimumDegree) {
            Object successor = minimumKey(right);
            node.keys[index] = successor;
            delete(right, cast(successor));
        } else {
            mergeChildren(node, index);
            delete(node.children[index], cast(target));
        }
    }

    private void removeKeyFromLeaf(Node node, int index) {
        for (int i = index; i < node.keyCount - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keys[node.keyCount - 1] = null;
        node.keyCount--;
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

        for (int i = child.keyCount - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (!child.isLeaf()) {
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
        if (!child.isLeaf()) {
            child.children[child.keyCount + 1] = sibling.children[0];
        }
        parent.keys[index] = sibling.keys[0];

        for (int i = 0; i < sibling.keyCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;

        if (!sibling.isLeaf()) {
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
        int leftCount = left.keyCount;

        left.keys[leftCount] = parent.keys[index];
        for (int i = 0; i < right.keyCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
        }

        if (!left.isLeaf()) {
            for (int i = 0; i <= right.keyCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
            }
        }
        left.keyCount += right.keyCount + 1;

        int oldParentCount = parent.keyCount;
        for (int i = index; i < oldParentCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[oldParentCount - 1] = null;

        for (int i = index + 1; i < oldParentCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[oldParentCount] = null;
        parent.keyCount--;
    }

    private Object minimumKey(Node node) {
        while (!node.isLeaf()) {
            node = node.children[0];
        }
        return node.keys[0];
    }

    private Object maximumKey(Node node) {
        while (!node.isLeaf()) {
            node = node.children[node.keyCount];
        }
        return node.keys[node.keyCount - 1];
    }

    private void copyInOrder(Node node, Object[] destination, int[] position) {
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.isLeaf()) {
                copyInOrder(node.children[i], destination, position);
            }
            destination[position[0]++] = node.keys[i];
        }
        if (!node.isLeaf()) {
            copyInOrder(node.children[node.keyCount], destination, position);
        }
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }

    @SuppressWarnings("unchecked")
    private T cast(Object value) {
        return (T) value;
    }
}
