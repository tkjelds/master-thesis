@BTree.ThreadSafe
public final class BTree<K extends Comparable<? super K>, V> {
    @interface ThreadSafe {}

    private static final class Node<K, V> {
        private final boolean leaf;
        private final Object[] keys;
        private final Object[] values;
        private final Object[] children;
        private int keyCount;

        private Node(int minimumDegree, boolean leaf) {
            this.leaf = leaf;
            this.keys = new Object[2 * minimumDegree - 1];
            this.values = new Object[2 * minimumDegree - 1];
            this.children = new Object[2 * minimumDegree];
            this.keyCount = 0;
        }
    }

    private final Object lock = new Object();
    private final int minimumDegree;
    private final int capacity;
    private volatile Node<K, V> root;
    private volatile int size;

    public BTree() {
        this(2, Integer.MAX_VALUE);
    }

    public BTree(int minimumDegree) {
        this(minimumDegree, Integer.MAX_VALUE);
    }

    public BTree(int minimumDegree, int capacity) {
        if (minimumDegree < 2 || minimumDegree > (Integer.MAX_VALUE / 2)) {
            throw new IllegalArgumentException("Minimum degree must be at least 2");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.minimumDegree = minimumDegree;
        this.capacity = capacity;
        this.root = new Node<K, V>(minimumDegree, true);
        this.size = 0;
    }

    public V put(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (lock) {
            if (findNode(key) == null && size >= capacity) {
                throw new IllegalStateException("B-tree is full");
            }
            return putInternal(key, value);
        }
    }

    public boolean offer(K key, V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (lock) {
            if (findNode(key) == null && size >= capacity) {
                return false;
            }
            putInternal(key, value);
            return true;
        }
    }

    public void putBlocking(K key, V value) throws InterruptedException {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        synchronized (lock) {
            while (findNode(key) == null && size >= capacity) {
                lock.wait();
            }
            putInternal(key, value);
        }
    }

    public V get(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            Node<K, V> node = findNode(key);
            if (node == null) {
                return null;
            }
            int index = lowerBound(node, key);
            return valueAt(node, index);
        }
    }

    public boolean containsKey(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            return findNode(key) != null;
        }
    }

    public V remove(K key) {
        requireNonNull(key, "key");
        synchronized (lock) {
            return removeInternal(key);
        }
    }

    public V takeMin() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> minNode = minimumNode(root);
            K minKey = keyAt(minNode, 0);
            return removeInternal(minKey);
        }
    }

    public V takeMax() throws InterruptedException {
        synchronized (lock) {
            while (size == 0) {
                lock.wait();
            }
            Node<K, V> maxNode = maximumNode(root);
            K maxKey = keyAt(maxNode, maxNode.keyCount - 1);
            return removeInternal(maxKey);
        }
    }

    public V pollMin() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> minNode = minimumNode(root);
            K minKey = keyAt(minNode, 0);
            return removeInternal(minKey);
        }
    }

    public V pollMax() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> maxNode = maximumNode(root);
            K maxKey = keyAt(maxNode, maxNode.keyCount - 1);
            return removeInternal(maxKey);
        }
    }

    public K minKey() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> minNode = minimumNode(root);
            return keyAt(minNode, 0);
        }
    }

    public K maxKey() {
        synchronized (lock) {
            if (size == 0) {
                return null;
            }
            Node<K, V> maxNode = maximumNode(root);
            return keyAt(maxNode, maxNode.keyCount - 1);
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return size == 0;
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return size >= capacity;
        }
    }

    public int capacity() {
        synchronized (lock) {
            return capacity;
        }
    }

    public int minimumDegree() {
        synchronized (lock) {
            return minimumDegree;
        }
    }

    public void clear() {
        synchronized (lock) {
            root = new Node<K, V>(minimumDegree, true);
            size = 0;
            lock.notifyAll();
        }
    }

    public Object[] keysInOrder() {
        synchronized (lock) {
            Object[] result = new Object[size];
            int[] index = new int[] { 0 };
            appendKeysInOrder(root, result, index);
            return result;
        }
    }

    private V putInternal(K key, V value) {
        Node<K, V> existingNode = findNode(key);
        if (existingNode != null) {
            int index = lowerBound(existingNode, key);
            V previous = valueAt(existingNode, index);
            existingNode.values[index] = value;
            return previous;
        }

        if (root.keyCount == maximumKeys()) {
            Node<K, V> newRoot = new Node<K, V>(minimumDegree, false);
            newRoot.children[0] = root;
            splitChild(newRoot, 0);
            root = newRoot;
        }

        insertNonFull(root, key, value);
        size++;
        lock.notifyAll();
        return null;
    }

    private V removeInternal(K key) {
        Node<K, V> existingNode = findNode(key);
        if (existingNode == null) {
            return null;
        }

        int index = lowerBound(existingNode, key);
        V previous = valueAt(existingNode, index);
        deleteFromNode(root, key);

        if (root.keyCount == 0 && !root.leaf) {
            root = childAt(root, 0);
        }

        size--;
        lock.notifyAll();
        return previous;
    }

    private void appendKeysInOrder(Node<K, V> node, Object[] output, int[] index) {
        if (node == null) {
            return;
        }
        for (int i = 0; i < node.keyCount; i++) {
            if (!node.leaf) {
                appendKeysInOrder(childAt(node, i), output, index);
            }
            output[index[0]++] = node.keys[i];
        }

        if (!node.leaf) {
            appendKeysInOrder(childAt(node, node.keyCount), output, index);
        }
    }

    private Node<K, V> findNode(K key) {
        Node<K, V> node = root;
        while (node != null) {
            int index = lowerBound(node, key);
            if (index < node.keyCount && compare(key, keyAt(node, index)) == 0) {
                return node;
            }
            if (node.leaf) {
                return null;
            }
            node = childAt(node, index);
        }
        return null;
    }

    private int lowerBound(Node<K, V> node, K key) {
        int low = 0;
        int high = node.keyCount;

        while (low < high) {
            int middle = low + (high - low) / 2;
            if (compare(keyAt(node, middle), key) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        return low;
    }

    private void insertNonFull(Node<K, V> node, K key, V value) {
        int index = node.keyCount - 1;

        if (node.leaf) {
            while (index >= 0 && compare(key, keyAt(node, index)) < 0) {
                node.keys[index + 1] = node.keys[index];
                node.values[index + 1] = node.values[index];
                index--;
            }

            node.keys[index + 1] = key;
            node.values[index + 1] = value;
            node.keyCount++;
            return;
        }

        while (index >= 0 && compare(key, keyAt(node, index)) < 0) {
            index--;
        }
        index++;

        Node<K, V> child = childAt(node, index);
        if (child.keyCount == maximumKeys()) {
            splitChild(node, index);
            if (compare(key, keyAt(node, index)) > 0) {
                index++;
            }
        }

        insertNonFull(childAt(node, index), key, value);
    }

    private void splitChild(Node<K, V> parent, int childIndex) {
        Node<K, V> fullChild = childAt(parent, childIndex);
        Node<K, V> newChild = new Node<K, V>(minimumDegree, fullChild.leaf);
        newChild.keyCount = minimumDegree - 1;

        for (int i = 0; i < minimumDegree - 1; i++) {
            newChild.keys[i] = fullChild.keys[i + minimumDegree];
            newChild.values[i] = fullChild.values[i + minimumDegree];
        }

        if (!fullChild.leaf) {
            for (int i = 0; i < minimumDegree; i++) {
                newChild.children[i] = fullChild.children[i + minimumDegree];
                fullChild.children[i + minimumDegree] = null;
            }
        }

        K medianKey = keyAt(fullChild, minimumDegree - 1);
        V medianValue = valueAt(fullChild, minimumDegree - 1);

        for (int i = fullChild.keyCount - 1; i >= minimumDegree - 1; i--) {
            fullChild.keys[i] = null;
            fullChild.values[i] = null;
        }
        fullChild.keyCount = minimumDegree - 1;

        for (int i = parent.keyCount; i >= childIndex + 1; i--) {
            parent.children[i + 1] = parent.children[i];
        }
        parent.children[childIndex + 1] = newChild;

        for (int i = parent.keyCount - 1; i >= childIndex; i--) {
            parent.keys[i + 1] = parent.keys[i];
            parent.values[i + 1] = parent.values[i];
        }
        parent.keys[childIndex] = medianKey;
        parent.values[childIndex] = medianValue;
        parent.keyCount++;
    }

    private void deleteFromNode(Node<K, V> node, K key) {
        int index = lowerBound(node, key);

        if (index < node.keyCount && compare(keyAt(node, index), key) == 0) {
            if (node.leaf) {
                removeKeyAt(node, index);
                return;
            }

            Node<K, V> leftChild = childAt(node, index);
            Node<K, V> rightChild = childAt(node, index + 1);

            if (leftChild.keyCount >= minimumDegree) {
                Node<K, V> predecessorNode = maximumNode(leftChild);
                int predecessorIndex = predecessorNode.keyCount - 1;
                K predecessorKey = keyAt(predecessorNode, predecessorIndex);
                V predecessorValue = valueAt(predecessorNode, predecessorIndex);
                node.keys[index] = predecessorKey;
                node.values[index] = predecessorValue;
                deleteFromNode(leftChild, predecessorKey);
            } else if (rightChild.keyCount >= minimumDegree) {
                Node<K, V> successorNode = minimumNode(rightChild);
                K successorKey = keyAt(successorNode, 0);
                V successorValue = valueAt(successorNode, 0);
                node.keys[index] = successorKey;
                node.values[index] = successorValue;
                deleteFromNode(rightChild, successorKey);
            } else {
                mergeChildren(node, index);
                deleteFromNode(leftChild, key);
            }
            return;
        }

        if (node.leaf) {
            return;
        }

        boolean wasLastChild = (index == node.keyCount);
        Node<K, V> child = childAt(node, index);

        if (child.keyCount == minimumDegree - 1) {
            fillChild(node, index);
        }

        if (wasLastChild && index > node.keyCount) {
            deleteFromNode(childAt(node, index - 1), key);
        } else {
            deleteFromNode(childAt(node, index), key);
        }
    }

    private void fillChild(Node<K, V> parent, int index) {
        if (index > 0 && childAt(parent, index - 1).keyCount >= minimumDegree) {
            borrowFromPrevious(parent, index);
        } else if (index < parent.keyCount
                && childAt(parent, index + 1).keyCount >= minimumDegree) {
            borrowFromNext(parent, index);
        } else if (index < parent.keyCount) {
            mergeChildren(parent, index);
        } else {
            mergeChildren(parent, index - 1);
        }
    }

    private void borrowFromPrevious(Node<K, V> parent, int index) {
        Node<K, V> child = childAt(parent, index);
        Node<K, V> sibling = childAt(parent, index - 1);

        for (int i = child.keyCount - 1; i >= 0; i--) {
            child.keys[i + 1] = child.keys[i];
            child.values[i + 1] = child.values[i];
        }

        if (!child.leaf) {
            for (int i = child.keyCount; i >= 0; i--) {
                child.children[i + 1] = child.children[i];
            }
            child.children[0] = sibling.children[sibling.keyCount];
            sibling.children[sibling.keyCount] = null;
        }

        child.keys[0] = parent.keys[index - 1];
        child.values[0] = parent.values[index - 1];
        parent.keys[index - 1] = sibling.keys[sibling.keyCount - 1];
        parent.values[index - 1] = sibling.values[sibling.keyCount - 1];

        sibling.keys[sibling.keyCount - 1] = null;
        sibling.values[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void borrowFromNext(Node<K, V> parent, int index) {
        Node<K, V> child = childAt(parent, index);
        Node<K, V> sibling = childAt(parent, index + 1);

        child.keys[child.keyCount] = parent.keys[index];
        child.values[child.keyCount] = parent.values[index];

        if (!child.leaf) {
            child.children[child.keyCount + 1] = sibling.children[0];
        }

        parent.keys[index] = sibling.keys[0];
        parent.values[index] = sibling.values[0];

        for (int i = 1; i < sibling.keyCount; i++) {
            sibling.keys[i - 1] = sibling.keys[i];
            sibling.values[i - 1] = sibling.values[i];
        }

        if (!sibling.leaf) {
            for (int i = 1; i <= sibling.keyCount; i++) {
                sibling.children[i - 1] = sibling.children[i];
            }
            sibling.children[sibling.keyCount] = null;
        }

        sibling.keys[sibling.keyCount - 1] = null;
        sibling.values[sibling.keyCount - 1] = null;
        child.keyCount++;
        sibling.keyCount--;
    }

    private void mergeChildren(Node<K, V> parent, int index) {
        Node<K, V> left = childAt(parent, index);
        Node<K, V> right = childAt(parent, index + 1);
        int leftCount = left.keyCount;

        left.keys[leftCount] = parent.keys[index];
        left.values[leftCount] = parent.values[index];

        for (int i = 0; i < right.keyCount; i++) {
            left.keys[leftCount + 1 + i] = right.keys[i];
            left.values[leftCount + 1 + i] = right.values[i];
        }

        if (!left.leaf) {
            for (int i = 0; i <= right.keyCount; i++) {
                left.children[leftCount + 1 + i] = right.children[i];
            }
        }

        left.keyCount = leftCount + right.keyCount + 1;

        for (int i = index + 1; i < parent.keyCount; i++) {
            parent.keys[i - 1] = parent.keys[i];
            parent.values[i - 1] = parent.values[i];
        }

        for (int i = index + 2; i <= parent.keyCount; i++) {
            parent.children[i - 1] = parent.children[i];
        }

        parent.keys[parent.keyCount - 1] = null;
        parent.values[parent.keyCount - 1] = null;
        parent.children[parent.keyCount] = null;
        parent.keyCount--;
    }

    private void removeKeyAt(Node<K, V> node, int index) {
        for (int i = index + 1; i < node.keyCount; i++) {
            node.keys[i - 1] = node.keys[i];
            node.values[i - 1] = node.values[i];
        }

        node.keyCount--;
        node.keys[node.keyCount] = null;
        node.values[node.keyCount] = null;
    }

    private Node<K, V> minimumNode(Node<K, V> node) {
        while (!node.leaf) {
            node = childAt(node, 0);
        }
        return node;
    }

    private Node<K, V> maximumNode(Node<K, V> node) {
        while (!node.leaf) {
            node = childAt(node, node.keyCount);
        }
        return node;
    }

    private int maximumKeys() {
        return 2 * minimumDegree - 1;
    }

    private int compare(K left, K right) {
        return left.compareTo(right);
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
    }

    @SuppressWarnings("unchecked")
    private K keyAt(Node<K, V> node, int index) {
        return (K) node.keys[index];
    }

    @SuppressWarnings("unchecked")
    private V valueAt(Node<K, V> node, int index) {
        return (V) node.values[index];
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> childAt(Node<K, V> node, int index) {
        return (Node<K, V>) node.children[index];
    }
}
