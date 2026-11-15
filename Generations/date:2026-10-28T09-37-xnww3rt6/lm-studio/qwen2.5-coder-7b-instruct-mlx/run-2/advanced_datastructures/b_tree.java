import java.lang.Comparable;

interface Order<T> {
    int compare(T left, T right);
}

@BTree.ThreadSafe
class BTree<T extends Comparable<T>> {
    @interface ThreadSafe {}

    private final Order<T> order;
    private final int minimumDegree;
    private Node<T> root;
    private volatile int size;

    public BTree(int minimumDegree, Order<T> order) {
        if (minimumDegree < 2 || minimumDegree > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("Invalid minimum degree");
        }
        this.minimumDegree = minimumDegree;
        this.order = order;
        this.root = new Node<>(minimumDegree, true);
    }

    public synchronized boolean add(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        root = add(root, item);
        if (root.isFull()) {
            Node<T> newRoot = new Node<>(minimumDegree, false);
            newRoot.children[0] = root;
            newRoot.splitChild(0, root);
            if (order.compare(newRoot.keys[0], item) <= 0) {
                root = newRoot.children[1];
            } else {
                root = newRoot;
            }
        }
        size++;
        return true;
    }

    private Node<T> add(Node<T> node, T item) {
        int index = findInsertionPoint(node.keys, order.compare(item, node.keys[0]));
        if (node.isLeaf()) {
            insertNonFull(node, index, item);
        } else {
            Node<T> child = add(node.children[index], item);
            if (child.isFull()) {
                node.splitChild(index, child);
                if (order.compare(node.keys[index], item) <= 0) {
                    index++;
                }
            }
        }
        return node;
    }

    public synchronized boolean remove(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        boolean result = remove(root, item);
        if (root != null && root.children.length == 1) {
            Node<T> temp = root;
            root = root.children[0];
        }
        if (result) {
            size--;
        }
        return result;
    }

    private boolean remove(Node<T> node, T item) {
        int index = findInsertionPoint(node.keys, order.compare(item, node.keys[0]));
        if (index < node.keys.length && item.equals(node.keys[index])) {
            if (node.isLeaf()) {
                deleteFromNode(node, index);
                return true;
            } else {
                Node<T> predecessor = node.children[index];
                while (!predecessor.isLeaf()) {
                    predecessor = predecessor.children[predecessor.keys.length];
                }
                node.keys[index] = predecessor.keys[0];
                return remove(predecessor, item);
            }
        } else if (index >= node.keys.length && !node.isLeaf()) {
            return remove(node.children[index], item);
        } else if (node.isLeaf()) {
            return false;
        }
        boolean shouldMerge = node.children[index].keys.length < minimumDegree;
        removeChild(node, index);
        if (shouldMerge && index > 0) {
            mergeChildren(node, index - 1);
        } else if (shouldMerge && index < node.keys.length) {
            mergeChildren(node, index);
        } else if (index > 0 && node.children[index - 1].keys.length >= minimumDegree) {
            borrowFromPrev(node, index);
        } else if (node.children.length > 1) {
            borrowFromNext(node, index);
        }
        return true;
    }

    private int findInsertionPoint(T[] keys, int comparison) {
        if (comparison <= 0) {
            return 0;
        } else if (keys.length < minimumDegree - 1) {
            return keys.length;
        }
        int low = 0, high = keys.length - 1;
        while (low <= high) {
            int mid = low + (high - low) / 2;
            int cmp = order.compare(keys[mid], keys[mid + 1]);
            if (cmp == comparison || (cmp < comparison && order.compare(keys[mid + 1], keys[mid]) <= 0)) {
                return mid + 1;
            }
            if (cmp < comparison) {
                low = mid + 2;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private void insertNonFull(Node<T> node, int index, T item) {
        for (int i = node.keys.length; i > index; i--) {
            node.keys[i] = node.keys[i - 1];
        }
        node.keys[index] = item;
        node.keysCount++;
    }

    private void deleteFromNode(Node<T> node, int index) {
        for (int i = index; i < node.keys.length - 1; i++) {
            node.keys[i] = node.keys[i + 1];
        }
        node.keysCount--;
    }

    private void removeChild(Node<T> node, int index) {
        for (int i = index; i < node.children.length - 1; i++) {
            node.children[i] = node.children[i + 1];
        }
        node.childrenCount--;
    }

    private void splitChild(int i, Node<T> oldNode) {
        Node<T> newNode = new Node<>(minimumDegree, oldNode.isLeaf());
        for (int j = 0; j < minimumDegree - 1; j++) {
            newNode.keys[j] = oldNode.keys[minimumDegree + j];
        }
        if (!oldNode.isLeaf()) {
            for (int j = 0; j < minimumDegree; j++) {
                newNode.children[j] = oldNode.children[minimumDegree + j];
            }
        }
        oldNode.keysCount = minimumDegree - 1;
        node.children[node.childrenCount] = newNode;
        for (int j = node.keys.length; j > i; j--) {
            node.children[j] = node.children[j - 1];
        }
        if (i <= node.keys.length) {
            node.children[i] = oldNode;
        }
        for (int j = node.keysCount; j > i - 1; j--) {
            node.keys[j] = node.keys[j - 1];
        }
        if (i <= node.keys.length) {
            node.keys[i - 1] = oldNode.keys[minimumDegree - 1];
        }
        node.keysCount++;
    }

    private void mergeChildren(int i, Node<T> node) {
        Node<T> child = node.children[i];
        Node<T> sibling = node.children[i + 1];
        child.keys[child.keysCount] = node.keys[i];
        for (int j = 0; j < sibling.keys.length; j++) {
            child.keys[child.keysCount + 1 + j] = sibling.keys[j];
        }
        if (!child.isLeaf()) {
            for (int j = 0; j <= sibling.children.length; j++) {
                child.children[child.keysCount + 1 + j] = sibling.children[j];
            }
        }
        child.keysCount += sibling.keys.length + 1;
        for (int j = i; j < node.keysCount - 1; j++) {
            node.keys[j] = node.keys[j + 1];
        }
        for (int j = i; j < node.childrenCount - 1; j++) {
            node.children[j] = node.children[j + 1];
        }
        node.keysCount--;
        node.childrenCount--;
    }

    private void borrowFromPrev(int i, Node<T> node) {
        Node<T> sibling = node.children[i - 1];
        for (int j = sibling.keys.length; j > 0; j--) {
            sibling.keys[j] = sibling.keys[j - 1];
        }
        if (!sibling.isLeaf()) {
            for (int j = sibling.children.length; j > 0; j--) {
                sibling.children[j] = sibling.children[j - 1];
            }
        }
        sibling.keys[0] = node.keys[i - 1];
        if (!node.isLeaf()) {
            sibling.children[0] = node.children[i].children[0];
        }
        node.keys[i - 1] = node.keys[i].keys[0];
        for (int j = 0; j < node.children[i].keys.length - 1; j++) {
            node.children[i].keys[j] = node.children[i].keys[j + 1];
        }
        for (int j = 0; j < node.children[i].children.length - 1; j++) {
            node.children[i].children[j] = node.children[i].children[j + 1];
        }
        sibling.keysCount++;
        node.children[i].keysCount--;
    }

    private void borrowFromNext(int i, Node<T> node) {
        Node<T> sibling = node.children[i + 1];
        node.keys[i] = node.keys[i].keys[sibling.keysCount - 1];
        for (int j = 0; j < sibling.keysCount - 2; j++) {
            sibling.keys[j] = sibling.keys[j + 1];
        }
        if (!node.isLeaf()) {
            for (int j = 0; j < sibling.childrenCount - 1; j++) {
                sibling.children[j] = sibling.children[j + 1];
            }
        }
        node.keys[i].keysCount = sibling.keysCount - 1;
        sibling.childrenCount--;
    }

    public synchronized boolean contains(T item) {
        if (item == null) {
            throw new NullPointerException("Null elements are not allowed");
        }
        return search(root, item) != -1;
    }

    private int search(Node<T> node, T item) {
        int i = 0;
        while (i < node.keys.length && order.compare(item, node.keys[i]) > 0) {
            i++;
        }
        if (i < node.keys.length && item.equals(node.keys[i])) {
            return i;
        }
        if (node.isLeaf()) {
            return -1;
        }
        return search(node.children[i], item);
    }

    public synchronized T first() {
        if (root == null || root.keysCount == 0) {
            throw new IllegalStateException("Tree is empty");
        }
        Node<T> current = root;
        while (!current.isLeaf()) {
            current = current.children[0];
        }
        return current.keys[0];
    }

    public synchronized T last() {
        if (root == null || root.keysCount == 0) {
            throw new IllegalStateException("Tree is empty");
        }
        Node<T> current = root;
        while (!current.isLeaf()) {
            current = current.children[current.keysCount];
        }
        return current.keys[current.keysCount - 1];
    }

    public synchronized int size() {
        return size;
    }

    public synchronized Object[] snapshot() {
        Object[] result = new Object[size];
        generateSnapshot(root, result, 0);
        return result;
    }

    private int generateSnapshot(Node<T> node, Object[] result, int index) {
        if (node.isLeaf()) {
            for (int i = 0; i < node.keysCount; i++) {
                result[index++] = node.keys[i];
            }
        } else {
            for (int i = 0; i < node.keysCount; i++) {
                index = generateSnapshot(node.children[i], result, index);
                result[index++] = node.keys[i];
            }
            index = generateSnapshot(node.children[node.keysCount], result, index);
        }
        return index;
    }

    private static class Node<T> {
        T[] keys;
        Node<T>[] children;
        boolean isLeaf;
        int keysCount;
        int childrenCount;

        public Node(int minimumDegree, boolean isLeaf) {
            this.minimumDegree = minimumDegree;
            this.isLeaf = isLeaf;
            keys = (T[]) new Comparable[2 * minimumDegree - 1];
            children = (Node<T>[]) new Node[2 * minimumDegree];
        }

        public boolean isFull() {
            return keysCount == 2 * minimumDegree - 1;
        }
    }
}
