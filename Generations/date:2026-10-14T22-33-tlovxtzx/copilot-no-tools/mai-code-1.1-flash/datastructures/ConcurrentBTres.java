@ConcurrentBTres.ThreadSafe
public final class ConcurrentBTres {
    @interface ThreadSafe {}

    private final Object monitor = new Object();
    private final int capacity;
    private final int minimumDegree;
    private Node root;
    private int size;

    public ConcurrentBTres(int capacity, int minimumDegree) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (minimumDegree < 2 || minimumDegree > (Integer.MAX_VALUE - 1) / 2) {
            throw new IllegalArgumentException("minimumDegree is out of range");
        }
        this.capacity = capacity;
        this.minimumDegree = minimumDegree;
        this.root = new Node(true);
        this.size = 0;
    }

    public boolean insert(int key) throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                if (containsInternal(key)) {
                    return false;
                }
                if (size < capacity) {
                    break;
                }
                monitor.wait();
            }

            if (root.keyCount == 2 * minimumDegree - 1) {
                Node newRoot = new Node(false);
                newRoot.children[0] = root;
                splitChild(newRoot, 0);
                root = newRoot;
            }

            insertNonFull(root, key);
            size++;
            monitor.notifyAll();
            return true;
        }
    }

    public boolean remove(int key) {
        synchronized (monitor) {
            if (!containsInternal(key)) {
                return false;
            }

            removeFromNode(root, key);

            if (root.keyCount == 0 && !root.leaf) {
                root = root.children[0];
            }

            size--;
            monitor.notifyAll();
            return true;
        }
    }

    public int takeMin() throws InterruptedException {
        synchronized (monitor) {
            while (size == 0) {
                monitor.wait();
            }

            int min = minimumKey(root);
            removeFromNode(root, min);

            if (root.keyCount == 0 && !root.leaf) {
                root = root.children[0];
            }

            size--;
            monitor.notifyAll();
            return min;
        }
    }

    public boolean contains(int key) {
        synchronized (monitor) {
            return containsInternal(key);
        }
    }

    public int size() {
        synchronized (monitor) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (monitor) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (monitor) {
            return size == capacity;
        }
    }

    public int capacity() {
        return capacity;
    }

    private boolean containsInternal(int key) {
        Node node = root;
        while (true) {
            int index = findKeyIndex(node, key);
            if (index < node.keyCount && node.keys[index] == key) {
                return true;
            }
            if (node.leaf) {
                return false;
            }
            node = node.children[index];
        }
    }

    private int findKeyIndex(Node node, int key) {
        int index = 0;
        while (index < node.keyCount && node.keys[index] < key) {
            index++;
        }
        return index;
    }

    private void insertNonFull(Node node, int key) {
        int index = node.keyCount - 1;

        if (node.leaf) {
            while (index >= 0 && node.keys[index] > key) {
                node.keys[index + 1] = node.keys[index];
                index--;
            }
            node.keys[index + 1] = key;
            node.keyCount++;
            return;
        }

        while (index >= 0 && node.keys[index] > key) {
            index--;
        }
        index++;

        if (node.children[index].keyCount == 2 * minimumDegree - 1) {
            splitChild(node, index);
            if (key > node.keys[index]) {
                index++;
            }
        }

        insertNonFull(node.children[index], key);
    }

    private void splitChild(Node parent, int childIndex) {
        Node fullChild = parent.children[childIndex];
        Node newChild = new Node(fullChild.leaf);
        newChild.keyCount = minimumDegree - 1;

        for (int i = 0; i < minimumDegree - 1; i++) {
            newChild.keys[i] = fullChild.keys[i + minimumDegree];
        }

        if (!fullChild.leaf) {
            for (int i = 0; i < minimumDegree; i++) {
                newChild.children[i] = fullChild.children[i + minimumDegree];
            }
        }

        fullChild.keyCount = minimumDegree - 1;

        for (int i = parent.keyCount; i >= childIndex + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = newChild;

        for (int i = parent.keyCount - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
        }

        parent.keys[childIndex] = fullChild.keys[minimumDegree - 1];
        parent.keyCount++;
    }

    private int minimumKey(Node node) {
        while (!node.leaf) {
            node = node.children[0];
        }
        return node.keys[0];
    }

    private void removeFromNode(Node node, int key) {
        int index = findKeyIndex(node, key);

        if (index < node.keyCount && node.keys[index] == key) {
            if (node.leaf) {
                removeKeyFromLeaf(node, index);
            } else {
                removeKeyFromInternalNode(node, index);
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        boolean wasLastChild = (index == node.keyCount);

        if (node.children[index].keyCount < minimumDegree) {
            fillChild(node, index);
        }

        if (wasLastChild && index > node.keyCount) {
            removeFromNode(node.children[index - 1], key);
        } else {
            removeFromNode(node.children[index], key);
        }
    }

    private void removeKeyFromLeaf(Node node, int index) {
        for (int i = index + 1; i < node.keyCount; i++) {
            node.keys[i - 1] = node.keys[i];
        }
        node.keyCount--;
    }

    private void removeKeyFromInternalNode(Node node, int index) {
        int key = node.keys[index];

        if (node.children[index].keyCount >= minimumDegree) {
            int predecessor = maximumKey(node.children[index]);
            node.keys[index] = predecessor;
            removeFromNode(node.children[index], predecessor);
        } else if (node.children[index + 1].keyCount >= minimumDegree) {
            int successor = minimumKey(node.children[index + 1]);
            node.keys[index] = successor;
            removeFromNode(node.children[index + 1], successor);
        } else {
            mergeChildren(node, index);
            removeFromNode(node.children[index], key);
        }
    }

    private int maximumKey(Node node) {
        while (!node.leaf) {
            node = node.children[node.keyCount];
        }
        return node.keys[node.keyCount - 1];
    }

    private void fillChild(Node parent, int childIndex) {
        if (childIndex > 0 && parent.children[childIndex - 1].keyCount >= minimumDegree) {
            borrowFromPrevious(parent, childIndex);
        } else if (childIndex < parent.keyCount && parent.children[childIndex + 1].keyCount >= minimumDegree) {
            borrowFromNext(parent, childIndex);
        } else if (childIndex < parent.keyCount) {
            mergeChildren(parent, childIndex);
        } else {
            mergeChildren(parent, childIndex - 1);
        }
    }

    private void borrowFromPrevious(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex - 1];

        for (int i = child.keyCount - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
        }
        if (!child.leaf) {
            for (int i = child.keyCount; i >= 0; i--) {
                child.children[i + 1] = child.children[i];
            }
        }

        child.keys[0] = parent.keys[childIndex - 1];
        if (!child.leaf) {
            child.children[0] = sibling.children[sibling.keyCount];
        }
        parent.keys[childIndex - 1] = sibling.keys[sibling.keyCount - 1];

        child.keyCount++;
        sibling.keyCount--;
    }

    private void borrowFromNext(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex + 1];

        child.keys[child.keyCount] = parent.keys[childIndex];
        if (!child.leaf) {
            child.children[child.keyCount + 1] = sibling.children[0];
        }
        parent.keys[childIndex] = sibling.keys[0];

        for (int i = 1; i < sibling.keyCount; i++) {
            sibling.keys[i - 1] = sibling.keys[i];
        }
        if (!sibling.leaf) {
            for (int i = 1; i <= sibling.keyCount; i++) {
                sibling.children[i - 1] = sibling.children[i];
            }
        }

        child.keyCount++;
        sibling.keyCount--;
    }

    private void mergeChildren(Node parent, int childIndex) {
        Node child = parent.children[childIndex];
        Node sibling = parent.children[childIndex + 1];
        int originalChildKeyCount = child.keyCount;

        child.keys[originalChildKeyCount] = parent.keys[childIndex];

        for (int i = 0; i < sibling.keyCount; i++) {
            child.keys[originalChildKeyCount + 1 + i] = sibling.keys[i];
        }

        if (!child.leaf) {
            for (int i = 0; i <= sibling.keyCount; i++) {
                child.children[originalChildKeyCount + 1 + i] = sibling.children[i];
            }
        }

        child.keyCount += sibling.keyCount + 1;

        for (int i = childIndex + 1; i < parent.keyCount; i++) {
            parent.keys[i - 1] = parent.keys[i];
        }
        for (int i = childIndex + 2; i <= parent.keyCount; i++) {
            parent.children[i - 1] = parent.children[i];
        }

        parent.keyCount--;
    }

    private static final class Node {
        private final int[] keys;
        private final Node[] children;
        private final boolean leaf;
        private int keyCount;

        private Node(boolean leaf) {
            this.leaf = leaf;
            int degree = ConcurrentBTres.thisMinimumDegree(); // not possible in static context
            this.keys = new int[2 * degree - 1];
            this.children = new Node[2 * degree];
            this.keyCount = 0;
        }
    }

    private static int thisMinimumDegree() {
        throw new UnsupportedOperationException("static helper is not valid");
    }
}
