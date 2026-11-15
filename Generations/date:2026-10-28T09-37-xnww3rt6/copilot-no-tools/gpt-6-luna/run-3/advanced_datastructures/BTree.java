@BTree.ThreadSafe
public class BTree<T> {
    @interface ThreadSafe {}

    public interface Order<E> {
        int compare(E left, E right);
    }

    private static final class Node {
        final Object[] keys;
        final Node[] children;
        int keyCount;

        Node(int maxKeys) {
            keys = new Object[maxKeys];
            children = new Node[maxKeys + 1];
        }

        boolean isLeaf() {
            return children[0] == null;
        }
    }

    private final Object lock = new Object();
    private final int minimumDegree;
    private final int maxKeys;
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
        this.order = order;
    }

    public boolean add(T item) {
        requireNonNull(item);
        synchronized (lock) {
            if (find(root, item) != null) {
                return false;
            }

            if (root == null) {
                root = new Node(maxKeys);
                root.keys[0] = item;
                root.keyCount = 1;
                elementCount++;
                return true;
            }

            if (root.keyCount == maxKeys) {
                Node newRoot = new Node(maxKeys);
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
        synchronized (lock) {
            if (find(root, item) == null) {
                return false;
            }

            deleteFrom(root, item);
            if (root.keyCount == 0) {
                root = root.children[0];
            }
            elementCount--;
            return true;
        }
    }

    public boolean contains(T item) {
        requireNonNull(item);
        synchronized (lock) {
            return find(root, item) != null;
        }
    }

    public T first() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node node = root;
            while (!node.isLeaf()) {
                node = node.children[0];
            }
            return cast(node.keys[0]);
        }
    }

    public T last() {
        synchronized (lock) {
            if (root == null) {
                throw new IllegalStateException("Tree is empty");
            }

            Node node = root;
            while (!node.isLeaf()) {
                node = node.children[node.keyCount];
            }
            return cast(node.keys[node.keyCount - 1]);
        }
    }

    public int size() {
        synchronized (lock) {
            return elementCount;
        }
    }

    public Object[] snapshot() {
        synchronized (lock) {
            Object[] result = new Object[elementCount];
            int[] position = new int[1];
            copyInOrder(root, result, position);
            return result;
        }
    }

    private void requireNonNull(T item) {
        if (item == null) {
            throw new NullPointerException("item");
        }
    }

    private Node find(Node node, T item) {
        while (node != null) {
            int index = 0;
            while (index < node.keyCount
                    && order.compare(cast(node.keys[index]), item) < 0) {
                index++;
            }

            if (index < node.keyCount
                    && order.compare(cast(node.keys[index]), item) == 0) {
                return node;
            }
            if (node.isLeaf()) {
                return null;
            }
            node = node.children[index];
        }
        return null;
    }

    private int findKeyIndex(Node node, T item) {
        int index = 0;
        while (index < node.keyCount
                && order.compare(cast(node.keys[index]), item) < 0) {
            index++;
        }
        return index;
    }

    private void splitChild(Node parent, int childIndex) {
        Node fullChild = parent.children[childIndex];
        Node right = new Node(maxKeys);
        int medianIndex = minimumDegree - 1;
        boolean internal = !fullChild.isLeaf();

        right.keyCount = minimumDegree - 1;
        for (int i = 0; i < minimumDegree - 1; i++) {
            right.keys[i] = fullChild.keys[i + minimumDegree];
            fullChild.keys[i + minimumDegree] = null;
        }
        Object median = fullChild.keys[medianIndex];
        fullChild.keys[medianIndex] = null;

        if (internal) {
            for (int i = 0; i < minimumDegree; i++) {
                right.children[i] = fullChild.children[i + minimumDegree];
                fullChild.children[i + minimumDegree] = null;
            }
        }
        fullChild.keyCount = minimumDegree - 1;

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
        int index = node.keyCount - 1;
        if (node.isLeaf()) {
            while (index >= 0
                    && order.compare(cast(node.keys[index]), item) > 0) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = item;
            node.keyCount++;
            return;
        }

        index = findKeyIndex(node, item);
        if (node.children[index].keyCount == maxKeys) {
            splitChild(node, index);
            if (order.compare(cast(node.keys[index]), item) < 0) {
                index++;
            }
        }
        insertNonFull(node.children[index], item);
    }

    private void deleteFrom(Node node, T item) {
        int index = findKeyIndex(node, item);

        if (index < node.keyCount
                && order.compare(cast(node.keys[index]), item) == 0) {
            if (node.isLeaf()) {
                removeKeyAt(node, index);
                return;
            }

            Node left = node.children[index];
            Node right = node.children[index + 1];
            if (left.keyCount >= minimumDegree) {
                T predecessor = maximum(left);
                node.keys[index] = predecessor;
                deleteFrom(left, predecessor);
            } else if (right.keyCount >= minimumDegree) {
                T successor = minimum(right);
                node.keys[index] = successor;
                deleteFrom(right, successor);
            } else {
                mergeChildren(node, index);
                deleteFrom(node.children[index], item);
            }
            return;
        }

        if (node.isLeaf()) {
            return;
        }

        if (node.children[index].keyCount == minimumDegree - 1) {
            if (index > 0
                    && node.children[index - 1].keyCount >= minimumDegree) {
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
        deleteFrom(node.children[index], item);
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
        boolean internal = !child.isLeaf();

        for (int i = child.keyCount - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (internal) {
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
        boolean internal = !child.isLeaf();
        int siblingCount = sibling.keyCount;

        child.keys[child.keyCount] = parent.keys[childIndex];
        if (internal) {
            child.children[child.keyCount + 1] = sibling.children[0];
        }
        parent.keys[childIndex] = sibling.keys[0];

        for (int i = 0; i < siblingCount - 1; i++) {
            sibling.keys[i] = sibling.keys[i + 1];
        }
        sibling.keys[siblingCount - 1] = null;

        if (internal) {
            for (int i = 0; i < siblingCount; i++) {
                sibling.children[i] = sibling.children[i + 1];
            }
            sibling.children[siblingCount] = null;
        }

        child.keyCount++;
        sibling.keyCount--;
    }

    private void mergeChildren(Node parent, int childIndex) {
        Node left = parent.children[childIndex];
        Node right = parent.children[childIndex + 1];
        int leftCount = left.keyCount;
        boolean internal = !left.isLeaf();

        left.keys[leftCount] = parent.keys[childIndex];
        for (int i = 0; i < right.keyCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            right.keys[i] = null;
        }
        if (internal) {
            for (int i = 0; i <= right.keyCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
                right.children[i] = null;
            }
        }
        left.keyCount = leftCount + right.keyCount + 1;

        int oldParentCount = parent.keyCount;
        for (int i = childIndex; i < oldParentCount - 1; i++) {
            parent.keys[i] = parent.keys[i + 1];
        }
        parent.keys[oldParentCount - 1] = null;

        for (int i = childIndex + 1; i < oldParentCount; i++) {
            parent.children[i] = parent.children[i + 1];
        }
        parent.children[oldParentCount] = null;
        parent.keyCount--;
    }

    private T maximum(Node node) {
        while (!node.isLeaf()) {
            node = node.children[node.keyCount];
        }
        return cast(node.keys[node.keyCount - 1]);
    }

    private T minimum(Node node) {
        while (!node.isLeaf()) {
            node = node.children[0];
        }
        return cast(node.keys[0]);
    }

    private void copyInOrder(Node node, Object[] destination, int[] position) {
        if (node == null) {
            return;
        }

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
    private T cast(Object value) {
        return (T) value;
    }
}
