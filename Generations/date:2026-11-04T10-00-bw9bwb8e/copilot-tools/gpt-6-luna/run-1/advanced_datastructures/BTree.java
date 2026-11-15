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
        final boolean leaf;

        Node(int maxKeys, int maxChildren, boolean leaf) {
            this.keys = new Object[maxKeys];
            this.children = new Node[maxChildren];
            this.leaf = leaf;
        }
    }

    private final int minimumDegree;
    private final int maxKeys;
    private final int maxChildren;
    private final Order<T> order;
    private final Object monitor = new Object();

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
        this.maxChildren = minimumDegree * 2;
        this.maxKeys = maxChildren - 1;
        this.order = order;
    }

    public boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }

        synchronized (monitor) {
            if (find(item)) {
                return false;
            }

            if (root == null) {
                root = new Node(maxKeys, maxChildren, true);
                root.keys[0] = item;
                root.keyCount = 1;
                size++;
                return true;
            }

            if (root.keyCount == maxKeys) {
                Node newRoot = new Node(maxKeys, maxChildren, false);
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
            if (root == null || !removeFromNode(root, item)) {
                return false;
            }

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
            return find(item);
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
            copyInOrder(root, result, 0);
            return result;
        }
    }

    private boolean find(T item) {
        Node node = root;
        while (node != null) {
            int index = lowerBound(node, item);
            if (index < node.keyCount && compare(keyAt(node, index), item) == 0) {
                return true;
            }
            node = node.leaf ? null : node.children[index];
        }
        return false;
    }

    private void insertNonFull(Node node, T item) {
        int index = lowerBound(node, item);
        if (node.leaf) {
            for (int i = node.keyCount; i > index; i--) {
                node.keys[i] = node.keys[i - 1];
            }
            node.keys[index] = item;
            node.keyCount++;
            return;
        }

        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            int comparison = compare(item, keyAt(node, index));
            if (comparison > 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void splitChild(Node parent, int index) {
        Node left = parent.children[index];
        Node right = new Node(maxKeys, maxChildren, left.leaf);
        int degree = minimumDegree;

        right.keyCount = degree - 1;
        for (int i = 0; i < degree - 1; i++) {
            right.keys[i] = left.keys[i + degree];
            left.keys[i + degree] = null;
        }

        if (!left.leaf) {
            for (int i = 0; i < degree; i++) {
                right.children[i] = left.children[i + degree];
                left.children[i + degree] = null;
            }
        }

        Object median = left.keys[degree - 1];
        left.keys[degree - 1] = null;
        left.keyCount = degree - 1;

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

    private boolean removeFromNode(Node node, T item) {
        int index = lowerBound(node, item);

        if (index < node.keyCount && compare(keyAt(node, index), item) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
                return true;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.keyCount >= minimumDegree) {
                T predecessor = maximumKey(left);
                node.keys[index] = predecessor;
                return removeFromNode(left, predecessor);
            }
            if (right.keyCount >= minimumDegree) {
                T successor = minimumKey(right);
                node.keys[index] = successor;
                return removeFromNode(right, successor);
            }

            mergeChildren(node, index);
            return removeFromNode(node.children[index], item);
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
                mergeChildren(node, index);
            } else {
                mergeChildren(node, index - 1);
                index--;
            }
        }

        return removeFromNode(node.children[index], item);
    }

    private void borrowFromPrevious(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex - 1];

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

        child.keys[0] = parent.keys[childIndex - 1];
        parent.keys[childIndex - 1] = sibling.keys[sibling.keyCount - 1];
        sibling.keys[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void borrowFromNext(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex + 1];
        int oldChildCount = child.keyCount;

        child.keys[oldChildCount] = parent.keys[childIndex];
        parent.keys[childIndex] = sibling.keys[0];

        if (!child.leaf) {
            child.children[oldChildCount + 1] = sibling.children[0];
            for (int i = 0; i < sibling.keyCount; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[sibling.keyCount] = null;
        }

        for (int i = 0; i < sibling.keyCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[sibling.keyCount - 1] = null;
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
            right.keys[i] = null;
        }

        if (!left.leaf) {
            for (int i = 0; i <= right.keyCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }
        left.keyCount += right.keyCount + 1;

        for (int i = index + 1; i < parent.keyCount; i++) {
            parent.keys[i - 1] = parent.keys[i];
        }
        for (int i = index + 2; i <= parent.keyCount; i++) {
            parent.children[i - 1] = parent.children[i];
        }
        parent.keyCount--;
        parent.keys[parent.keyCount] = null;
        parent.children[parent.keyCount + 1] = null;
    }

    private void removeKeyAt(Node node, int index) {
        for (int i = index + 1; i < node.keyCount; i++) {
            node.keys[i - 1] = node.keys[i];
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

    private int lowerBound(Node node, T item) {
        int low = 0;
        int high = node.keyCount;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (compare(keyAt(node, middle), item) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private int copyInOrder(Node node, Object[] destination, int position) {
        if (node == null) {
            return position;
        }

        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                position = copyInOrder(node.children[i], destination, position);
            }
            destination[position++] = node.keys[i];
        }
        if (!node.leaf) {
            position = copyInOrder(node.children[node.keyCount], destination, position);
        }
        return position;
    }

    private int compare(T left, T right) {
        return order.compare(left, right);
    }

    @SuppressWarnings("unchecked")
    private T keyAt(Node node, int index) {
        return (T) node.keys[index];
    }
}
